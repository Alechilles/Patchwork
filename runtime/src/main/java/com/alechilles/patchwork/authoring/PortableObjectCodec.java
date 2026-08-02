package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Set;
import javax.annotation.Nonnull;
import org.bson.BsonValue;

/** Removes Hytale editor metadata fields from codecs for portable JSON objects. */
final class PortableObjectCodec<T> implements Codec<T> {
    private final Codec<T> delegate;
    private final Set<String> portableFields;

    PortableObjectCodec(Codec<T> delegate, Set<String> portableFields) {
        this.delegate = delegate;
        this.portableFields = Set.copyOf(portableFields);
    }

    @Override
    public T decode(BsonValue value, ExtraInfo extraInfo) {
        return delegate.decode(value, extraInfo);
    }

    @Override
    public BsonValue encode(T value, ExtraInfo extraInfo) {
        return delegate.encode(value, extraInfo);
    }

    @Override
    public T decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return delegate.decodeJson(reader, extraInfo);
    }

    @Nonnull
    @Override
    public ObjectSchema toSchema(@Nonnull SchemaContext context) {
        ObjectSchema schema = (ObjectSchema) delegate.toSchema(context);
        var filtered = new LinkedHashMap<String, com.hypixel.hytale.codec.schema.config.Schema>();
        for (var entry : schema.getProperties().entrySet()) {
            if (portableFields.contains(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
        }
        schema.setProperties(filtered);
        schema.setAdditionalProperties(false);
        return schema;
    }
}
