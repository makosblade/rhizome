package com.kryptnostic.rhizome.configuration.service;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.s3.AmazonS3;
import com.auth0.jwt.internal.org.apache.commons.io.IOUtils;
import com.dataloom.mappers.ObjectMappers;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.io.Resources;
import com.kryptnostic.rhizome.configuration.ConfigurationKey;
import com.kryptnostic.rhizome.configuration.SimpleConfigurationKey;
import com.kryptnostic.rhizome.configuration.annotation.ReloadableConfiguration;

/**
 * Configuration service API for getting, setting, and registering for configuration updates.
 *
 * @author Matthew Tamayo-Rios
 */
public interface ConfigurationService {
    public void registerModule( Module module );

    /**
     * Retrieves an existing configuration. The configuration class must have a static {@code getKey()} method that will
     * be used as key to lookup the YAML configuration.
     *
     * @param clazz
     * @return The configuration if it can be found, null otherwise.
     * @throws IOException
     */
    public abstract @Nullable <T> T getConfiguration( Class<T> clazz ) throws IOException;

    /**
     * Creates or updates a configuration and fires a configuration update event to all subscribers.
     *
     * @param configuration The configuration to be updated or created
     * @throws IOException
     */
    public abstract <T> void setConfiguration( T configuration ) throws IOException;

    /**
     * Registers a subscriber that will receive updated configuration events at methods annotated with guava's
     * {@code @Subscribe} annotation.
     *
     * @param subscriber
     */
    // public abstract <T extends Configuration> s<Configuration> getAllConfigurations();

    public abstract void subscribe( Object subscriber );

    public final static class StaticLoader {
        private static final Logger       logger = LoggerFactory.getLogger( StaticLoader.class );
        private static final ObjectMapper mapper = ObjectMappers.getYamlMapper();

        private StaticLoader() {}

        /**
         * Static method for loading a configuration before the service has been instantiated. This is useful for
         * bootstrapping an application context or a database connection, which the Configuration service may depend on.
         * The configuration class must have a static {@code getKey()} method that will be used as the name of the
         * resource containing the YAML configuration.
         *
         * @param clazz - The configuration class to load.
         * @return The configuration if it can successfully loaded, null otherwise.
         */
        public static @Nullable <T> T loadConfiguration( Class<T> clazz ) {
            ConfigurationKey key = getConfigurationKey( Preconditions.checkNotNull(
                    clazz,
                    "Cannot load configuration for null class." ) );

            if ( key == null ) {
                logger.error( "Unable to load key for configuration class {}", clazz.getName() );
                return null;
            }

            return loadConfigurationFromResource( key, clazz );
        }

        public static @Nullable ConfigurationKey getConfigurationKey( Class<?> clazz ) {
            String uri = getReloadableConfigurationUri( clazz );
            if ( StringUtils.isNotBlank( uri ) ) {
                return new SimpleConfigurationKey( uri );
            }

            /*
             * This requires a static method called key on the class. Unfortunately, in Java 7 it cannot be enforced.
             */
            try {
                Method keyGetter = Preconditions.checkNotNull( clazz.getMethod( "key" ), clazz.getName()
                        + " is missing required static method key()." );
                return (ConfigurationKey) keyGetter.invoke( null );
            } catch ( InvocationTargetException nfe ) {
                logger.error( clazz.getName() + " is missing required static method key().", nfe );
                return null;
            } catch ( Exception e ) {
                logger.error( "Unable to determine configuration id for class " + clazz.getName(), e );
                return null;
            }
        }

        static String getReloadableConfigurationUri( Class<?> clazz ) {
            ReloadableConfiguration config = clazz.getAnnotation( ReloadableConfiguration.class );
            if ( config != null ) {
                if ( StringUtils.isBlank( config.uri() ) ) {
                    return clazz.getCanonicalName();
                } else {
                    return config.uri();
                }
            } else {
                return null;
            }
        }

        static @Nullable <T> T loadConfigurationFromResource(
                ConfigurationKey key,
                Class<T> clazz ) {
            T s = null;
            String yamlString = null;

            try {
                try {
                    URL resource = Resources.getResource( key.getUri() );
                    yamlString = Resources.toString( resource, StandardCharsets.UTF_8 );
                    if ( StringUtils.isBlank( yamlString ) ) {
                        throw new IOException( "Unable to read configuration from classpath." );
                    }
                } catch ( IOException | IllegalArgumentException e ) {
                    logger.warn( "Failed to load resource from " + key.getUri(), e );
                }
                s = mapper.readValue( yamlString, clazz );
            } catch ( IOException e ) {
                logger.error( "Failed to load default configuration for " + key.getUri(), e );
            }

            return s;
        }

        public static @Nullable <T> T loadConfigurationFromS3(
                AmazonS3 s3,
                String bucket,
                String folder,
                Class<T> clazz ) {
            T s = null;
            String key = getReloadableConfigurationUri( clazz );
            String yamlString = null;
            try {
                try {
                    yamlString = IOUtils.toString( s3.getObject( bucket, folder + key ).getObjectContent() );
                } catch ( IOException | IllegalArgumentException e ) {
                    logger.debug( "Failed to load resource from " + key, e );
                }
                if ( StringUtils.isBlank( yamlString ) ) {
                    throw new IOException( "Unable to read configuration from classpath." );
                }
                s = mapper.readValue( yamlString, clazz );
            } catch ( IOException e ) {
                logger.debug( "Failed to load default configuration for " + key, e );
            }

            return s;
        }
    }
}