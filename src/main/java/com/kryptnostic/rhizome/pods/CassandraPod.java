package com.kryptnostic.rhizome.pods;

import com.datastax.driver.core.*;
import com.datastax.driver.core.Cluster.Builder;
import com.google.common.annotations.VisibleForTesting;
import com.kryptnostic.rhizome.cassandra.CassandraTableBuilder;
import com.kryptnostic.rhizome.cassandra.TableDef;
import com.kryptnostic.rhizome.configuration.RhizomeConfiguration;
import com.kryptnostic.rhizome.configuration.cassandra.*;
import jersey.repackaged.com.google.common.collect.Maps;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

import javax.inject.Inject;
import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Configuration
@Profile( CassandraPod.CASSANDRA_PROFILE )
@Import( ConfigurationPod.class )
public class CassandraPod {
    public static final  String CASSANDRA_PROFILE = "cassandra";
    public static final  String CREATE_KEYSPACE   = "CREATE KEYSPACE IF NOT EXISTS %s WITH REPLICATION={ 'class' : 'SimpleStrategy', 'replication_factor' : %d } AND DURABLE_WRITES=true";
    private static final Logger logger            = LoggerFactory.getLogger( CassandraPod.class );

    @Inject
    private RhizomeConfiguration configuration;

    @Autowired(
            required = false )
    private Set<TypeCodec<?>> codecs;

    @Autowired(
            required = false )
    private Set<TableDefSource> tables;

    private static PoolingOptions getPoolingOptions() {
        PoolingOptions poolingOptions = new PoolingOptions();
        return poolingOptions;
    }

    public static Builder clusterBuilder( CassandraConfiguration cassandraConfiguration ) {
        Builder builder = new Cluster.Builder();
        builder.withCompression( cassandraConfiguration.getCompression() )
                .withPoolingOptions( getPoolingOptions() )
                .withProtocolVersion( ProtocolVersion.NEWEST_SUPPORTED )
                .addContactPoints( cassandraConfiguration.getCassandraSeedNodes() );
        if ( cassandraConfiguration.isSslEnabled() ) {
            SSLContext context = null;
            try {
                context = SSLContextBuilder.create().loadTrustMaterial( new TrustSelfSignedStrategy() ).build();
            } catch ( NoSuchAlgorithmException | KeyStoreException | KeyManagementException e ) {
                logger.error( "Unable to configure SSL for Cassanda Java Driver" );
            }
            builder.withSSL( JdkSSLOptions.builder().withSSLContext( context ).build() );
        }
        return builder;
    }

    @Bean
    public CassandraConfiguration cassandraConfiguration() {
        if ( configuration == null || !configuration.getCassandraConfiguration().isPresent() ) {
            throw new RuntimeException(
                    "Cassandra configuration is missing. Please add a cassandra configuration to rhizome.yaml" );
        }
        CassandraConfiguration cassandraConfiguration = configuration.getCassandraConfiguration().get();
        if ( cassandraConfiguration == null ) {
            logger.error(
                    "Seed nodes not found in cassandra configuration. Please add seed nodes to cassandra configuration block in rhizome.yaml" );
        } else {
            logger.info( "Using the following seeds for cassandra: {}",
                    cassandraConfiguration.getCassandraSeedNodes().stream().map( s -> s.getHostAddress() )
                            .collect( Collectors.toList() ) );
        }
        return cassandraConfiguration;
    }

    @Bean
    public Session session() {
        Session session = cluster().newSession();
        CassandraConfiguration cc = cassandraConfiguration();
        session.execute( String.format( CREATE_KEYSPACE, cc.getKeyspace(), cc.getReplicationFactor() ) );
        // Create all the required tables.
        if ( tables != null ) {
            logger.info( "Setting up tables and secondary indices." );
            tables.stream()
                    .flatMap( Supplier::get )
                    .map( TableDef::getBuilder )
                    .map( CassandraTableBuilder::build )
                    .flatMap( List::stream )
                    .peek( query -> logger.info( "Executing query: {}", query ) )
                    .forEach( session::execute );
        }

        return session;
    }

    @Bean
    public CodecRegistry codecRegistry() {
        // TODO: Explicitly construct default instance codecs so that not all sessions end up having all codecs. P2
        CodecRegistry registry = CodecRegistry.DEFAULT_INSTANCE;
        if ( codecs != null ) {
            registry.register( codecs );
        }
        return registry;
    }

    @Bean
    public Cluster cluster() {
        return clusterBuilder( cassandraConfiguration() )
                .withCodecRegistry( codecRegistry() )
                .build();
    }

    @Bean
    public Clusters clusters() {
        return new Clusters( Maps.transformValues( getConfigurations(),
                cassandraConfiguration -> clusterBuilder( cassandraConfiguration )
                        .withCodecRegistry( codecRegistry() )
                        .build() ) );
    }

    @Bean
    public Sessions sessions() {
        return new Sessions( Maps.transformValues( clusters(), cluster -> cluster.newSession() ) );
    }

    @VisibleForTesting
    public void setRhizomeConfig( RhizomeConfiguration config ) {
        this.configuration = config;
    }

    @VisibleForTesting
    public void setCodecs( Set<TypeCodec<?>> codecs ) {
        this.codecs = codecs;
    }

    private CassandraConfigurations getConfigurations() {
        return configuration.getCassandraConfigurations().or( new CassandraConfigurations() );
    }
}
