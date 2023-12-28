package org.microshed.testing.jwt;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.microshed.testing.internal.InternalLogger;
import org.microshed.testing.jupiter.MicroShedTestExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class JwtConfigExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final InternalLogger LOG = InternalLogger.get(JwtConfigExtension.class);

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        configureJwt(context);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        removeJwt(context);
    }

    private void configureJwt(ExtensionContext context) throws Exception {

        // Check if the test method has the @JwtConfig annotation
        Method testMethod = context.getTestMethod().orElse(null);
        if (testMethod != null) {

            // Check if RestAssured is being used
            Class<?> restAssuredClass = tryLoad("io.restassured.RestAssured");
            if (restAssuredClass == null) {
                LOG.debug("RESTAssured not found!");
            } else {

                LOG.debug("RESTAssured found!");

                JwtConfig jwtConfig = testMethod.getAnnotation(JwtConfig.class);
                if (jwtConfig != null) {
                    // Configure RestAssured with the values from @JwtConfig for each test method
                    LOG.info("JWTConfig on method: " + testMethod.getName());
                    // Get the RequestSpecBuilder class
                    Class<?> requestSpecBuilderClass = Class.forName("io.restassured.builder.RequestSpecBuilder");
                    // Create an instance of RequestSpecBuilder
                    Object requestSpecBuilder = requestSpecBuilderClass.newInstance();
                    // Get the requestSpecification field
                    Field requestSpecificationField = restAssuredClass.getDeclaredField("requestSpecification");
                    requestSpecificationField.setAccessible(true);

                    // Get the header method of RequestSpecBuilder
                    Method headerMethod = requestSpecBuilderClass.getDeclaredMethod("addHeader", String.class, String.class);

                    try {
                        String jwt = JwtBuilder.buildJwt(jwtConfig.subject(), jwtConfig.issuer(), jwtConfig.claims());
                        headerMethod.invoke(requestSpecBuilder, "Authorization", "Bearer " + jwt);
                        LOG.debug("Using provided JWT auth header: " + jwt);
                    } catch (Exception e) {
                        throw new ExtensionConfigurationException("Error while building JWT for method " + testMethod.getName() + " with JwtConfig: " + jwtConfig, e);
                    }

                    // Set the updated requestSpecification
                    requestSpecificationField.set(null, requestSpecBuilderClass.getMethod("build").invoke(requestSpecBuilder));
                }
            }
        }
    }

    private void removeJwt(ExtensionContext context) throws Exception {
        // Check if the test method has the @JwtConfig annotation
        Method testMethod = context.getTestMethod().orElse(null);
        if (testMethod != null) {
            LOG.debug("Method was annotated with: " + testMethod.getName());


            // Check if RestAssured is being used
            Class<?> restAssuredClass = tryLoad("io.restassured.RestAssured");
            if (restAssuredClass == null) {
                LOG.debug("RESTAssured not found!");
            } else {
                // Get the requestSpecification field
                Field requestSpecificationField = restAssuredClass.getDeclaredField("requestSpecification");
                requestSpecificationField.setAccessible(true);

                // Removes all requestSpec
                requestSpecificationField.set(null, null);
            }
        }
    }

    private static Class<?> tryLoad(String clazz) {
        try {
            return Class.forName(clazz, false, MicroShedTestExtension.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
