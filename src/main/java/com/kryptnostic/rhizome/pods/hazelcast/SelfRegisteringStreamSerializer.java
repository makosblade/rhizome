package com.kryptnostic.rhizome.pods.hazelcast;

import com.hazelcast.nio.serialization.StreamSerializer;

public interface SelfRegisteringStreamSerializer<T> extends StreamSerializer<T> {
    Class<T> getClazz();
}
