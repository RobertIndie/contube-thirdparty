package com.zikeyang.contube.pulsar;

import com.zikeyang.contube.api.Context;
import com.zikeyang.contube.api.Sink;
import com.zikeyang.contube.api.TubeRecord;
import com.zikeyang.contube.common.Utils;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.impl.schema.AutoConsumeSchema;
import org.apache.pulsar.common.protocol.schema.BytesSchemaVersion;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.util.Reflections;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;

@Slf4j
public class PulsarConnectSourceTube implements Sink {
  final AutoConsumeSchema schema = new AutoConsumeSchema();
  PulsarSinkConfig config;
  ClassLoader connectorClassLoader;
  org.apache.pulsar.io.core.Sink sink;
  Context context;

  @Override
  public void open(Map<String, Object> map, Context context) {
    this.context = context;
    config = Utils.loadConfig(map, PulsarSinkConfig.class);
    String narArchive = config.getArchive();
    Map<String, Object> connectorConfig = config.getConnectorConfig();
    try {
      connectorClassLoader = PulsarUtils.extractClassLoader(narArchive);
      String sinkClassName =
          PulsarUtils.getSinkClassName(config.getClassName(), connectorClassLoader);
      sink = (org.apache.pulsar.io.core.Sink<?>) Reflections.createInstance(sinkClassName,
          connectorClassLoader);
    } catch (Exception e) {
      throw new RuntimeException("Unable to load the connector", e);
    }
    SinkContext sinkContext = createSinkContext();
    ClassLoader defaultClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(connectorClassLoader);
    try {
      sink.open(connectorConfig, sinkContext);
    } catch (Exception e) {
      log.error("Sink open produced uncaught exception: ", e);
      throw new RuntimeException("Unable to open the connector", e);
    } finally {
      Thread.currentThread().setContextClassLoader(defaultClassLoader);
    }
  }

  SinkContext createSinkContext() {
    return new PulsarSinkContext(context.getName(), config);
  }

  @Override
  public void write(TubeRecord record) {
    Schema<?> internalSchema = null;
    if (record.getSchemaData().isPresent()) {
      SchemaInfo schemaInfo = PulsarUtils.convertToSchemaInfo(record.getSchemaData().get());
      internalSchema = Schema.getSchema(schemaInfo);
      schema.setSchema(BytesSchemaVersion.of(new byte[0]), internalSchema);
    }
    GenericObject genericObject = schema.decode(record.getValue(), null);
    Schema<?> finalInternalSchema = internalSchema;
    Record<?> sinkRecord = createRecord(genericObject, finalInternalSchema);
    ClassLoader defaultClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(connectorClassLoader);
    try {
      sink.write(sinkRecord);
    } catch (Exception e) {
      log.info("Encountered exception in sink write: ", e);
    } finally {
      Thread.currentThread().setContextClassLoader(defaultClassLoader);
    }
  }

  Record<?> createRecord(GenericObject genericObject, Schema<?> internalSchema) {
    return new Record() {
      @Override
      public Object getValue() {
        return genericObject;
      }

      @Override
      public Schema<?> getSchema() {
        return internalSchema;
      }
    };
  }

  @Override
  public void close() throws Exception {
    if (sink != null) {
      sink.close();
    }
  }
}
