package org.apache.iceberg.mr.mapred;

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.StandaloneHiveRunner;
import com.klarna.hiverunner.annotations.HiveSQL;
import java.util.HashMap;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.hive.HiveCatalogs;
import org.apache.iceberg.hive.TestHiveMetastore;
import org.apache.iceberg.types.Types;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(StandaloneHiveRunner.class)
public class TestHiveStorageHandler {
  @HiveSQL(files = {}, autoStart = false)
  private HiveShell shell;

  private static TestHiveMetastore metastore;
  private static HiveCatalog catalog;
  private static HiveMetaStoreClient metastoreClient;

  @BeforeClass
  public static void setup() throws Exception {
    metastore = new TestHiveMetastore();
    metastore.start();

    HiveConf conf = metastore.hiveConf();
    // create test db
    metastoreClient = new HiveMetaStoreClient(conf);
    String dbPath = metastore.getDatabasePath("db");
    Database db = new Database("db", "description", dbPath, new HashMap<>());
    metastoreClient.createDatabase(db);
    catalog = HiveCatalogs.loadCatalog(conf);
  }

  @Test
  public void testHiveMetadataReads() {
    // setup HiveRunner
    shell.start();

    Schema schema = new Schema(
        Types.NestedField.required(0, "id", Types.LongType.get()),
        Types.NestedField.optional(1, "data", Types.StringType.get()),
        Types.NestedField.optional(2, "date", Types.StringType.get())

    );
    PartitionSpec spec = PartitionSpec.builderFor(schema)
                                      .identity("data")
                                      .identity("date")
                                      .build();
    // write data..

    Table table = catalog.createTable(TableIdentifier.of("db", "t"), schema, spec);
    shell.executeQuery("create table with sto");
    for (String s : shell.executeQuery("describe formatted db.t")) {
      System.out.println(s);
    }
    shell.executeQuery("select * from db.t");
  }
}
