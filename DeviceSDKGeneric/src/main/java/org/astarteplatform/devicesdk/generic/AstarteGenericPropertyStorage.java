package org.astarteplatform.devicesdk.generic;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.table.TableUtils;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.astarteplatform.devicesdk.AstartePropertyStorage;
import org.astarteplatform.devicesdk.protocol.AstarteInterface;
import org.bson.BSONCallback;
import org.bson.BSONDecoder;
import org.bson.BSONObject;
import org.bson.BasicBSONCallback;
import org.bson.BasicBSONDecoder;
import org.bson.BsonBinaryWriter;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.joda.time.DateTime;

class AstarteGenericPropertyStorage implements AstartePropertyStorage {
  private final BSONDecoder mBSONDecoder = new BasicBSONDecoder();
  private final BSONCallback mBSONCallback = new BasicBSONCallback();
  private Dao<AstarteGenericPropertyEntry, String> mPropertyEntryDao;

  AstarteGenericPropertyStorage(Dao<AstarteGenericPropertyEntry, String> propertyEntryDao)
      throws SQLException {
    TableUtils.createTableIfNotExists(
        propertyEntryDao.getConnectionSource(), AstarteGenericPropertyEntry.class);
    mPropertyEntryDao = propertyEntryDao;
  }

  @Override
  public List<String> getStoredPathsForInterface(String astarteInterfaceName) {
    List<String> returnedPaths = new ArrayList<>();
    QueryBuilder<AstarteGenericPropertyEntry, String> statementBuilder =
        mPropertyEntryDao.queryBuilder();
    synchronized (this) {
      try {
        statementBuilder
            .where()
            .eq(AstarteGenericPropertyEntry.INTERFACE_FIELD_NAME, astarteInterfaceName);
        List<AstarteGenericPropertyEntry> result =
            mPropertyEntryDao.query(statementBuilder.prepare());
        for (AstarteGenericPropertyEntry entry : result) {
          returnedPaths.add(entry.getPath());
        }
      } catch (SQLException e) {
        // TODO: make exception surface to the SDK
        e.printStackTrace();
      }
    }

    return returnedPaths;
  }

  @Override
  public Map<String, Object> getStoredValuesForInterface(AstarteInterface astarteInterface) {
    Map<String, Object> returnedValues = new HashMap<>();
    QueryBuilder<AstarteGenericPropertyEntry, String> statementBuilder =
        mPropertyEntryDao.queryBuilder();
    synchronized (this) {
      try {
        statementBuilder
            .where()
            .eq(
                AstarteGenericPropertyEntry.INTERFACE_FIELD_NAME,
                astarteInterface.getInterfaceName());
        List<AstarteGenericPropertyEntry> result =
            mPropertyEntryDao.query(statementBuilder.prepare());
        for (AstarteGenericPropertyEntry entry : result) {
          Object value = deserialize(entry.getBSONValue());
          returnedValues.put(entry.getPath(), value);
        }
      } catch (SQLException e) {
        // TODO: make exception surface to the SDK
        e.printStackTrace();
      }
    }

    return returnedValues;
  }

  private Object deserialize(byte[] bsonValue) {
    // Parse the BSON payload
    mBSONCallback.reset();
    mBSONDecoder.decode(bsonValue, mBSONCallback);
    BSONObject decodedPayload = (BSONObject) mBSONCallback.get();
    // Parse the BSON value
    return toJavaType((BsonValue) decodedPayload.get("v"));
  }

  private Object toJavaType(BsonValue value) {
    switch (value.getBsonType()) {
      case INT32:
        return value.asInt32().getValue();
      case INT64:
        return value.asInt64().getValue();
      case STRING:
        return value.asString().getValue();
      case DECIMAL128:
        return value.asDecimal128().doubleValue();
      case DOUBLE:
        return value.asDouble().getValue();
      case BOOLEAN:
        return value.asBoolean().getValue();
      case OBJECT_ID:
        return value.asObjectId().getValue();
      case BINARY:
        return value.asBinary().getData();
      case DATE_TIME:
        return new DateTime(value.asDateTime().getValue());
      case SYMBOL:
        return value.asSymbol().getSymbol();
      case ARRAY:
        return value.asArray().toArray();
      case DOCUMENT:
        return Document.parse(value.asDocument().toJson());
      default:
        return value;
    }
  }

  @Override
  public void setStoredValue(String interfaceName, String path, Object value) {
    byte[] bsonValue = serialize(value);
    AstarteGenericPropertyEntry entry =
        new AstarteGenericPropertyEntry(interfaceName, path, bsonValue);
    synchronized (this) {
      try {
        mPropertyEntryDao.createOrUpdate(entry);
      } catch (SQLException e) {
        // TODO: make exception surface to the SDK
        e.printStackTrace();
      }
    }
  }

  private byte[] serialize(Object value) {
    HashMap<String, Object> bsonJavaObject = new HashMap<>();

    if (value instanceof DateTime) {
      // Special case for DateTime
      bsonJavaObject.put("v", ((DateTime) value).toDate());
    } else {
      bsonJavaObject.put("v", value);
    }
    Document bsonDocument = new Document(bsonJavaObject);

    BasicOutputBuffer out = new BasicOutputBuffer();
    byte[] documentAsByteArray = null;

    try (BsonBinaryWriter w = new BsonBinaryWriter(out)) {
      new DocumentCodec().encode(w, bsonDocument, EncoderContext.builder().build());
      documentAsByteArray = out.toByteArray();
    }

    return documentAsByteArray;
  }

  @Override
  public void removeStoredPath(String interfaceName, String path) {
    synchronized (this) {
      try {
        mPropertyEntryDao.deleteById(interfaceName + "/" + path);
      } catch (SQLException e) {
        // TODO: make exception surface to the SDK
        e.printStackTrace();
      }
    }
  }

  @Override
  public void purgeProperties(Map<String, List<String>> availableProperties) {
    synchronized (this) {
      // When we get this, we want to clear all keys which aren't part of the received list.
      for (Map.Entry<String, List<String>> entry : availableProperties.entrySet()) {
        for (String storedPath : getStoredPathsForInterface(entry.getKey())) {
          if (!entry.getValue().contains(storedPath)) {
            // Purge!
            removeStoredPath(entry.getKey(), storedPath);
          }
        }
      }
    }
  }
}