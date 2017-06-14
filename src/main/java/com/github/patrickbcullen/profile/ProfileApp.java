package com.github.patrickbcullen.profile;

import com.sun.scenario.effect.impl.prism.PrFilterContext;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.StateStoreSupplier;
import org.apache.kafka.streams.processor.TopologyBuilder;
import org.apache.kafka.streams.state.Stores;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


public class ProfileApp {

    static final String PROFILE_EVENTS_TOPIC = "profile.events";
    static final String PROFILE_STORE_TOPIC = "profile.topic";
    public static final String PROFILE_STORE_NAME = "profile.store";
    public static final String SEARCH_STORE_NAME = "search.store";

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length == 1) {
            port = Integer.valueOf(args[0]);
        }
        final String bootstrapServers = args.length > 1 ? args[1] : "localhost:9092";

        Properties streamsConfiguration = new Properties();
        // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
        // against which the application is run.
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "profiles-service");
        // Where to find Kafka broker(s).
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Provide the details of our embedded http service that we'll use to connect to this streams
        // instance and discover locations of stores.
        streamsConfiguration.put(StreamsConfig.APPLICATION_SERVER_CONFIG, "localhost:" + port);
        final File example = Files.createTempDirectory(new File("/tmp").toPath(), "kafka-state").toFile();
        streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, example.getPath());

        final KafkaStreams streams = createStreams(streamsConfiguration);
        // Always (and unconditionally) clean local state prior to starting the processing topology.
        // We opt for this unconditional call here because this will make it easier for you to play around with the example
        // when resetting the application for doing a re-run (via the Application Reset Tool,
        // http://docs.confluent.io/current/streams/developer-guide.html#application-reset-tool).
        //
        // The drawback of cleaning up local state prior is that your app must rebuilt its local state from scratch, which
        // will take time and will require reading all the state-relevant data from the Kafka cluster over the network.
        // Thus in a production scenario you typically do not want to clean up always as we do here but rather only when it
        // is truly needed, i.e., only under certain conditions (e.g., the presence of a command line flag for your app).
        // See `ApplicationResetExample.java` for a production-like example.
        streams.cleanUp();
        // Now that we have finished the definition of the processing topology we can actually run
        // it via `start()`.  The Streams application as a whole can be launched just like any
        // normal Java application that has a `main()` method.
        streams.start();

        // Start the Restful proxy for servicing remote access to state stores
        final ProfileService restService = startRestProxy(streams, port, bootstrapServers);

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                streams.close();
                restService.stop();
            } catch (Exception e) {
                // ignored
            }
        }));
    }


    static ProfileService startRestProxy(final KafkaStreams streams, final int port, final String bootstrapServers) throws Exception {
        final ProfileService profileService = new ProfileService(streams, PROFILE_EVENTS_TOPIC, PROFILE_STORE_NAME, SEARCH_STORE_NAME, bootstrapServers);
        profileService.start(port);
        return profileService;
    }

    static KafkaStreams createStreams(final Properties streamsConfiguration) {
        Serde<ProfileEvent> profileEventSerde = createJSONSerde(ProfileEvent.class);
        Serde<ProfileBean> profileBeanSerde = createJSONSerde(ProfileBean.class);
        Serde<String> stringSerde = Serdes.String();
        StateStoreSupplier profileStoreSupplier = Stores.create(PROFILE_STORE_NAME)
                .withKeys(stringSerde)
                .withValues(profileBeanSerde)
                .persistent()
                .build();

        StateStoreSupplier searchStoreSupplier = Stores.create(SEARCH_STORE_NAME)
                .withKeys(stringSerde)
                .withValues(profileBeanSerde)
                .persistent()
                .build();
        TopologyBuilder builder = new TopologyBuilder();
        builder.addSource("EventSource", stringSerde.deserializer(), profileEventSerde.deserializer(), PROFILE_EVENTS_TOPIC)
                .addProcessor("EventProcessor", () -> new ProfileEventProcessor(), "EventSource")
                .addStateStore(profileStoreSupplier, "EventProcessor")
                .addStateStore(searchStoreSupplier, "EventProcessor");

        return new KafkaStreams(builder, streamsConfiguration);
    }

    static <T> Serde<T> createJSONSerde(Class<T> clazz) {
        Map<String, Object> serdeProps = new HashMap<>();

        final Serializer<T> serializer = new JsonPOJOSerializer<>();
        serdeProps.put("JsonPOJOClass", clazz);
        serializer.configure(serdeProps, false);

        final Deserializer<T> deserializer = new JsonPOJODeserializer<>();
        serdeProps.put("JsonPOJOClass", clazz);
        deserializer.configure(serdeProps, false);

        final Serde<T> serde = Serdes.serdeFrom(serializer, deserializer);
        return serde;
    }
}

