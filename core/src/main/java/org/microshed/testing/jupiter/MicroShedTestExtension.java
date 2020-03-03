/*
 * Copyright (c) 2019,2020 IBM Corporation and others
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.microshed.testing.jupiter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.microshed.testing.ApplicationEnvironment;
import org.microshed.testing.SharedContainerConfig;
import org.microshed.testing.jaxrs.BasicAuthConfig;
import org.microshed.testing.jaxrs.RESTClient;
import org.microshed.testing.jaxrs.RestClientBuilder;
import org.microshed.testing.jwt.JwtBuilder;
import org.microshed.testing.jwt.JwtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit Jupiter extension that is applied whenever the <code>@MicroProfileTest</code> is used on a test class.
 * Currently this is tied to Testcontainers managing runtime build/deployment, but in a future version
 * it could be refactored to allow for a different framework managing the runtime build/deployment.
 */
class MicroShedTestExtension implements BeforeAllCallback {

    static final Logger LOG = LoggerFactory.getLogger(MicroShedTestExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();

        // Explicitly trigger static initialization of any SharedContainerConfig before we do further processing
        if (testClass.isAnnotationPresent(SharedContainerConfig.class)) {
            Class.forName(testClass.getAnnotation(SharedContainerConfig.class).value().getName());
        }

        ApplicationEnvironment config = ApplicationEnvironment.Resolver.load();
        LOG.info("Using ApplicationEnvironment class: " + config.getClass().getCanonicalName());
        config.applyConfiguration(testClass);
        config.start();
        configureRestAssured(config);
        injectRestClients(testClass);
    }

    private static void injectRestClients(Class<?> clazz) {
        List<Field> restClientFields = new ArrayList<>();
        restClientFields.addAll(AnnotationSupport.findAnnotatedFields(clazz, RESTClient.class));
        // Also tolerate people using the MicroProfile @RestClient annotation instead
        getMpRestClient().ifPresent(mpRestClient -> {
            restClientFields.addAll(AnnotationSupport.findAnnotatedFields(clazz, mpRestClient));
        });
        if (restClientFields.size() == 0)
            return;

        for (Field restClientField : restClientFields) {
            if (!Modifier.isPublic(restClientField.getModifiers()) ||
                !Modifier.isStatic(restClientField.getModifiers()) ||
                Modifier.isFinal(restClientField.getModifiers())) {
                throw new ExtensionConfigurationException("REST client field must be public, static, and non-final: " + restClientField);
            }
            RestClientBuilder rcBuilder = new RestClientBuilder();
            JwtConfig jwtAnno = restClientField.getDeclaredAnnotation(JwtConfig.class);
            BasicAuthConfig basicAnno = restClientField.getDeclaredAnnotation(BasicAuthConfig.class);
            if (jwtAnno != null && basicAnno != null)
                throw new ExtensionConfigurationException("Can only use one of @JwtConfig or @BasicAuthConfig on REST client field: " + restClientField);

            if (jwtAnno != null) {
                try {
                    String jwt = JwtBuilder.buildJwt(jwtAnno.subject(), jwtAnno.issuer(), jwtAnno.claims());
                    rcBuilder.withJwt(jwt);
                } catch (Exception e) {
                    throw new ExtensionConfigurationException("Error while building JWT for field " + restClientField + " with JwtConfig: " + jwtAnno, e);
                }
            }
            if (basicAnno != null) {
                rcBuilder.withBasicAuth(basicAnno.user(), basicAnno.password());
            }

            Object restClient = rcBuilder.build(restClientField.getType());
            try {
                restClientField.set(null, restClient);
                LOG.debug("Injected rest client for " + restClientField);
            } catch (Exception e) {
                throw new ExtensionConfigurationException("Unable to set field " + restClientField, e);
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void configureRestAssured(ApplicationEnvironment config) {
        if (!config.configureRestAssured())
            return;

        Class<?> RestAssured = tryLoad("io.restassured.RestAssured");
        if (RestAssured == null)
            return;

        try {
            URL appURL = new URL(config.getApplicationURL());
            String baseURI = appURL.getProtocol() + "://" + appURL.getHost();
            int port = appURL.getPort();
            String basePath = appURL.getPath();
            LOG.info("Configuring RestAssured with baseURI=" + baseURI + "  port=" + port + "  basePath=" + basePath);

            RestAssured.getField("baseURI").set(null, baseURI);
            RestAssured.getField("basePath").set(null, basePath);
            RestAssured.getField("port").set(null, port);
        } catch (Exception e) {
            LOG.warn("Unable to configure REST Assured because of: " + e.getMessage(), e);
        }

        try {
            // Configure JSONB as the JSON object mapper by invoking:
            //   ObjectMapperType JSONB = ObjectMapperType.JSONB;
            //   ObjectMapperConfig omConfig = ObjectMapperConfig.objectMapperConfig().defaultObjectMapperType(JSONB);
            //   RestAssured.config = RestAssured.config.objectMapperConfig(omConfig);
            ClassLoader cl = MicroShedTestExtension.class.getClassLoader();
            Class<Enum> ObjectMapperType = (Class<Enum>) Class.forName("io.restassured.mapper.ObjectMapperType", false, cl);
            Object JSONB = Enum.valueOf(ObjectMapperType, "JSONB");
            Class<?> ObjectMapperConfig = Class.forName("io.restassured.config.ObjectMapperConfig", false, cl);
            Object omConfig = ObjectMapperConfig.getMethod("objectMapperConfig").invoke(null);
            omConfig = omConfig.getClass().getMethod("defaultObjectMapperType", ObjectMapperType).invoke(omConfig, JSONB);
            Class<?> RestAssuredConfig = Class.forName("io.restassured.config.RestAssuredConfig", false, cl);
            Object raConfig = RestAssured.getField("config").get(null);
            raConfig = RestAssuredConfig.getMethod("objectMapperConfig", ObjectMapperConfig).invoke(raConfig, omConfig);
            RestAssured.getField("config").set(null, raConfig);
            LOG.debug("Regsitered JSONB ObjectMapper for REST Assured");
        } catch (IllegalArgumentException e) {
            // Prior to RestAssured 4.2.0 the ObjectMapperType.JSONB enum is not available
            LOG.debug("Unable to configure JSON-B object mapper for REST Assured due to: " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("Unable to configure JSON-B object mapper for REST Assured", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<Class<? extends Annotation>> getMpRestClient() {
        return Optional.ofNullable((Class<? extends Annotation>) tryLoad("org.eclipse.microprofile.rest.client.inject.RestClient"));
    }

    private static Class<?> tryLoad(String clazz) {
        try {
            return Class.forName(clazz, false, MicroShedTestExtension.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
