package com.github.geequery.codegen;

import java.io.File;

import jef.database.DbClient;
import jef.database.datasource.SimpleDataSource;

import org.junit.Test;

public class ExportTest {
    DbClient db=new DbClient(new SimpleDataSource("jdbc:mysql://api.hikvision.com.cn:3306/api", "root", "88075998"));
	@Test
	public void test123() throws Exception{
		DbExporter ex=new DbExporter();
		ex.doxport(db, "com.github.geequery.codegen.entity",new File("src/main/resources"));
		
	}
}
