package com.github.geequery.codegen;

import java.io.File;

import jef.database.DbClient;

import org.junit.Test;

public class ExportTest {
	@Test
	public void test123() throws Exception{
		DbExporter ex=new DbExporter();
		ex.doxport(new DbClient(), "",new File("src/main/resources"));
	}
}
