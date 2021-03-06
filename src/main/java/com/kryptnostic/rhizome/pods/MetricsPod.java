package com.kryptnostic.rhizome.pods;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.annotation.Timed;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.kryptnostic.rhizome.configuration.RhizomeConfiguration;
import com.kryptnostic.rhizome.configuration.graphite.GraphiteConfiguration;
import com.ryantenney.metrics.spring.config.annotation.EnableMetrics;
import com.ryantenney.metrics.spring.config.annotation.MetricsConfigurer;

/**
 * @author Matthew Tamayo-Rios
 */
@Configuration
@EnableMetrics(
    proxyTargetClass = true )
@Import(
    value = AsyncPod.class )
public class MetricsPod implements MetricsConfigurer {
    private static final Logger              logger              = LoggerFactory.getLogger( MetricsPod.class );
    private static final MetricRegistry      metricRegistry      = new MetricRegistry();
    private static final HealthCheckRegistry healthCheckRegistry = new HealthCheckRegistry();

    @Inject
    private RhizomeConfiguration             config;

    @Bean
    @Timed
    public GraphiteReporter serverGraphiteReporter() {
        Graphite graphite = serverGraphite();

        if ( graphite == null ) {
            return null;
        }

        try {
            graphite.connect();
        } catch ( IOException e ) {
            return null;
        }
        return GraphiteReporter.forRegistry( metricRegistry ).prefixedWith( getHostName() )
                .convertDurationsTo( TimeUnit.MILLISECONDS ).convertRatesTo( TimeUnit.SECONDS )
                .build( graphite );
    }

    @Bean
    public ConsoleReporter consoleGraphiteReporter() {
        if ( config.getGraphiteConfiguration().isPresent() ) {
            GraphiteConfiguration graphiteConfig = config.getGraphiteConfiguration().get();
            if ( graphiteConfig.isEnableConsole() ) {
                return ConsoleReporter.forRegistry( metricRegistry )
                        .convertDurationsTo( TimeUnit.MILLISECONDS ).convertRatesTo( TimeUnit.SECONDS )
                        .build();
            }
        }
        return null;
    }

    @Override
    public void configureReporters( MetricRegistry registry ) {
        /* No-Op */
    }

    @Override
    public HealthCheckRegistry getHealthCheckRegistry() {
        return healthCheckRegistry;
    }

    @Override
    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    @Bean
    public Graphite serverGraphite() {
        if ( config.getGraphiteConfiguration().isPresent() ) {
            GraphiteConfiguration graphiteConfig = config.getGraphiteConfiguration().get();
            logger.info(
                    "Initializing server graphite instance with at {}:{}",
                    graphiteConfig.getGraphiteHost(),
                    graphiteConfig.getGraphitePort() );
            return new Graphite( new InetSocketAddress(
                    graphiteConfig.getGraphiteHost(),
                    graphiteConfig.getGraphitePort() ) );
        }
        return null;
    }

    @Autowired(
        required = false )
    public void startGraphite( Set<ScheduledReporter> reporters ) {
        reporters.forEach( reporter -> {
            if ( reporter != null ) {
                reporter.start( 10, TimeUnit.SECONDS );
            }
        } );
    }

    protected String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch ( UnknownHostException e ) {
            logger.warn( "Unable to determine hostname, default to Hazelcast UUID", e );
            return null;
        }
    }

    protected String getGlobalName() {
        if ( config.getGraphiteConfiguration().isPresent() ) {
            return config.getGraphiteConfiguration().get().getGraphiteGlobalPrefix();
        }
        return "global";
    }
}
