package com.github.geequery.codegen;

import java.io.File;
import java.sql.SQLException;
import java.util.Arrays;

import jef.codegen.EntityEnhancer;
import jef.database.DataObject;
import jef.database.DbClient;
import jef.database.ORMConfig;
import jef.database.datasource.SimpleDataSource;
import jef.database.jpa.JefEntityManagerFactory;
import jef.database.support.QuerableEntityScanner;
import jef.tools.string.RandomData;

import org.junit.Test;

import com.github.geequery.codegen.MetaProvider.DbClientProvider;
import com.github.geequery.codegen.testid.Foo;

public class ExportTest {
    @Test
    public void testGenerateSource() throws Exception {
        final DbClient db = new DbClient(new SimpleDataSource("jdbc:mysql://api.hikvision.com.cn:3306/api?useUnicode=true&characterEncoding=UTF-8", "root",
                "88075998"));
        EntityGenerator g = new EntityGenerator();
        g.setProfile(db.getProfile());
        g.addExcludePatter(".*_\\d+$"); // 防止出现分表
        g.addExcludePatter("AAA"); // 排除表
        g.setMaxTables(999);
        g.setSrcFolder(new File(System.getProperty("user.dir"), "src/test/java"));
        g.setBasePackage("com.github.geequery.codegen.entity");
        g.setProvider(new DbClientProvider(db) {
        });
        g.generateSchema();
        db.shutdown();
    }

    @Test
    public void testExporterData() throws Exception {
        DbClient db = new DbClient(new SimpleDataSource("jdbc:mysql://api.hikvision.com.cn:3306/api", "root", "88075998"));
        DbExporter ex = new DbExporter();
        ex.exportPackage(db, "com.github.geequery.codegen.entity", new File(System.getProperty("user.dir"), "src/test/resources"));
        db.shutdown();
    }

    @Test
    public void testInitData() {
        EntityEnhancer en = new EntityEnhancer();
        en.enhance("com.github.geequery.codegen.entity");
        ORMConfig.getInstance().setDebugMode(true);
        DbClient db = new DbClient(new SimpleDataSource("jdbc:mysql://api.hikvision.com.cn:3306/test1?useUnicode=true&characterEncoding=UTF-8", "root",
                "88075998"));
        QuerableEntityScanner qe = new QuerableEntityScanner();
        qe.setImplClasses(DataObject.class);
        qe.setAllowDropColumn(true);
        qe.setAlterTable(true);
        qe.setCreateTable(true);
        qe.setEntityManagerFactory(new JefEntityManagerFactory(db), false);
        qe.setPackageNames("com.github.geequery.codegen.entity");
        qe.doScan();
        qe.finish();
        db.shutdown();
    }

    @Test
    public void testGeneratedSeq() throws Exception {
        EntityEnhancer en = new EntityEnhancer();
        en.enhance("com.github.geequery.codegen.testid");
        DbClient db = new DbClient(new SimpleDataSource("jdbc:mysql://api.hikvision.com.cn:3306/test1", "root", "88075998"));
        DbExporter ex = new DbExporter();
        ex.exportPackage(db, "com.github.geequery.codegen.testid", new File(System.getProperty("user.dir"), "src/test/resources"));
//        db.createTable(Foo.class);
        
    }

}
