package jef.database.support;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jef.common.log.LogUtil;
import jef.database.DbClient;
import jef.database.DbMetaData;
import jef.database.DbUtils;
import jef.database.IQueryableEntity;
import jef.database.ORMConfig;
import jef.database.QB;
import jef.database.meta.ITableMetadata;
import jef.database.meta.MetaHolder;
import jef.jre5support.ProcessUtil;
import jef.tools.StringUtils;
import jef.tools.csvreader.Codecs;
import jef.tools.csvreader.CsvReader;
import jef.tools.reflect.Property;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.geequery.orm.annotation.InitializeData;

public class DataInitializer {
	private DbClient session;
	private volatile boolean enable = true;
	private Logger log = LoggerFactory.getLogger(DataInitializer.class);
	private boolean useTable;

	private int tableInit;
	private int recordInit;
	private String extension;
	private String globalCharset = "UTF-8";

	public DataInitializer(DbClient session, boolean useTable, String charset, String extName) {
		this.session = session;
		this.extension = "." + extName;
		if (charset != null)
			this.globalCharset = charset;
		DbMetaData meta = session.getMetaData(null);
		try {
			if (useTable) {
				meta.createTable(MetaHolder.getMeta(AllowDataInitialize.class));
				checkEnableFlag();
			} else {
				// 即便没有打开useDataInitTable的开关，只要检测到这张表存在，就认为开关已经打开。
				if (meta.existTable("allow_data_initialize")) {
					session.refreshTable(AllowDataInitialize.class);
					useTable = true;
					LogUtil.info("Table [allow_data_initialize] was found, will turn [geequery.useDataInitTable=true] on.");			
					checkEnableFlag();
				}
			}
		} catch (SQLException e) {
			throw DbUtils.toRuntimeException(e);
		}
		this.useTable = useTable;
	}

	private void checkEnableFlag() throws SQLException {
		AllowDataInitialize record = session.load(QB.create(AllowDataInitialize.class), false);
		if (record == null) {
			createRecord();
		} else {
			enable = record.isDoInit();
		}
		LogUtil.info("Table [allow_data_initialize] was found, DataInit flag = {}.", enable);
	}

	private void createRecord() throws SQLException {
		AllowDataInitialize record = new AllowDataInitialize();
		record.setDoInit(true);
		session.insert(record);
		enable = true;
	}

	private List<IQueryableEntity> readData(ITableMetadata meta, URL url, String charset) throws UnsupportedEncodingException, IOException {
		CsvReader reader = new CsvReader(new InputStreamReader(url.openStream(), charset));
		try {
			// 根据Header分析Property
			List<Property> props = new ArrayList<Property>();
			if (reader.readHeaders()) {
				for (String header : reader.getHeaders()) {
					if (header.charAt(0) == '[') {
						header = header.substring(1, header.length() - 1);
					}
					jef.database.Field field = meta.getField(header);
					if (field == null) {
						throw new IllegalArgumentException(String.format("The field [%s] in CSV file doesn't exsts in the entity [%s] metadata.", header,
								meta.getName()));
					}
					props.add(meta.getColumnDef(field).getFieldAccessor());
				}
			}
			// 根据预先分析好的Property读取属性
			List<IQueryableEntity> result = new ArrayList<IQueryableEntity>();
			while (reader.readRecord()) {
				IQueryableEntity obj = meta.newInstance();
				// obj.stopUpdate();
				for (int i = 0; i < props.size(); i++) {
					Property prop = props.get(i);
					prop.set(obj, Codecs.fromString(reader.get(i), prop.getGenericType()));
				}
				// obj.startUpdate();
				result.add(obj);
			}
			return result;
		} finally {
			reader.close();
		}
	}

	private int initData0(ITableMetadata meta, URL url, String charset, boolean manualSequence) throws IOException {
		int count = 0;
		List<IQueryableEntity> data = readData(meta, url, charset);
		boolean value = ORMConfig.getInstance().isManualSequence();
		if (value != manualSequence)
			ORMConfig.getInstance().setManualSequence(manualSequence);
		try {
			for (int i = 0; i < data.size(); i += 500) {
				int batchIndex = Math.min(i + 500, data.size());
				try {
					session.batchInsert(data.subList(i, batchIndex));
					count += (batchIndex - i);
				} catch (SQLIntegrityConstraintViolationException e1) {
					// 主键冲突，改为逐条插入
					count += insertOnebyone(data.subList(i, batchIndex));
				} catch (SQLException e1) {
					throw DbUtils.toRuntimeException(e1);
				}
			}
		} finally {
			if (value != manualSequence)
				ORMConfig.getInstance().setManualSequence(value);
		}
		return count;
	}

	private int insertOnebyone(List<IQueryableEntity> data) {
		int count = 0;
		for (IQueryableEntity e : data) {
			try {
				session.insert(e);
				count++;
			} catch (SQLException e1) {
				log.error("Insert error:{}", e, e1);
			}
		}
		return count;
	}

	private int mergeData0(ITableMetadata meta, URL url, String charset, boolean manualSequence, String[] mergeKey) throws IOException {
		int count = 0;
		boolean valueBackup = ORMConfig.getInstance().isManualSequence();
		// 如果manualSequence和默认配置不同，那么修改后再初始化，完成后改回来
		if (valueBackup != manualSequence)
			ORMConfig.getInstance().setManualSequence(manualSequence);
		try {
			for (IQueryableEntity e : readData(meta, url, charset)) {
				try {
					IQueryableEntity result = session.merge(e, mergeKey);
					if (result == null || result != e) {
						count++;
					}
				} catch (SQLException e1) {
					log.error("Insert error:{}", e, e1);
				}
			}
		} finally {
			if (valueBackup != manualSequence) {
				ORMConfig.getInstance().setManualSequence(valueBackup);
			}
		}
		return count;
	}

	public final boolean isEnable() {
		return enable;
	}

	/**
	 * 对外暴露。初始化制定表的数据
	 * 
	 * @param meta
	 *            表结构元数据
	 * @param isNew
	 *            表是否刚刚创建
	 */
	public final void initData(ITableMetadata meta, boolean isNew) {
		String csvResouce = "/" + meta.getThisType().getName() + extension;
		boolean ensureResourceExists = false;
		String charset = this.globalCharset;
		String tableName = meta.getTableName(false);
		boolean manualSequence = false;
		String sqlResouce = "";
		String[] mergeKeys = null;
		InitializeData config = meta.getThisType().getAnnotation(InitializeData.class);
		if (config != null) {
			if (!config.enable()) {
				log.info("Table [{}] was's disabled on DataInitilalize feature by Annotation @InitializeData", tableName);
				return;
			}
			if (StringUtils.isNotEmpty(config.value())) {
				csvResouce = config.value();
			}
			if (StringUtils.isNotEmpty(config.charset())) {
				charset = config.charset();
			}
			if (config.mergeKeys().length > 0) {
				mergeKeys = config.mergeKeys();
			}
			ensureResourceExists = config.ensureFileExists();
			manualSequence = config.manualSequence();
			sqlResouce = config.sqlFile();
		}
		if (StringUtils.isEmpty(sqlResouce)) {
			initCSVData(meta, isNew, csvResouce, manualSequence, ensureResourceExists, charset, mergeKeys);
		} else {
			initSqlData(meta, isNew, sqlResouce, ensureResourceExists, charset);
		}
	}

	private void initSqlData(ITableMetadata meta, boolean isNew, String resName, boolean ensureResourceExists, String charset) {
		String tableName = meta.getTableName(false);
		URL url = meta.getThisType().getResource(resName);
		if (url == null) {
			if (ensureResourceExists) {
				throw new IllegalStateException("Resource of table [" + tableName + "] was not found:" + resName);
			}
			return;
		}
		try {
			session.getMetaData(null).executeScriptFile(url);
		} catch (SQLException e) {
			throw DbUtils.toRuntimeException(e);
		}
	}

	private void initCSVData(ITableMetadata meta, boolean isNew, String resName, boolean manualSequence, boolean ensureResourceExists, String charset,
			String[] mergeKey) {
		String tableName = meta.getTableName(false);
		URL url = meta.getThisType().getResource(resName);
		if (url != null) {
			try {
				if (isNew) {
					log.info("Table [{}] was created just now, begin insert data into database.", tableName);
					int n = initData0(meta, url, charset, manualSequence);
					recordInit += n;
					log.info("Table [{}] dataInit completed. {} records inserted.", tableName, n);
				} else {
					log.info("Table [{}] already exists, begin merge data into database.", tableName);
					int n = mergeData0(meta, url, charset, manualSequence, mergeKey);
					recordInit += n;
					log.info("Table [{}] dataInit completed. {} records saved.", tableName, n);
				}
				tableInit++;

			} catch (RuntimeException e) {
				ex = e;
				throw e;
			} catch (IOException e) {
				ex = e;
				throw new IllegalStateException(e);
			}
		} else if (ensureResourceExists) {
			throw new IllegalStateException("Resource of table [" + tableName + "] was not found:" + resName);
		} else {
			log.debug("Data file was not found:{}", resName);
		}

	}

	/**
	 * 记录初始化任务结果
	 */
	private void recordResult(String message) {
		if (recordInit > 0) {
			try {
				AllowDataInitialize record = session.load(QB.create(AllowDataInitialize.class), false);
				record.getQuery().setAllRecordsCondition();
				record.prepareUpdate(AllowDataInitialize.Field.doInit, false);
				record.prepareUpdate(AllowDataInitialize.Field.lastDataInitTime, new Date());
				record.prepareUpdate(AllowDataInitialize.Field.lastDataInitUser,
						ProcessUtil.getPid() + "@" + ProcessUtil.getHostname() + "(" + ProcessUtil.getLocalIp() + ") OS:" + ProcessUtil.getOSName());
				record.prepareUpdate(AllowDataInitialize.Field.lastDataInitResult, StringUtils.truncate(message, 300));
				session.update(record);
			} catch (SQLException e) {
				log.error("Record DataInitilizer Table failure! please check.", e);
			}
		}
	}

	private Exception ex;

	public void finish() {
		String message;
		if (ex == null) {
			message = "success. Tables init = " + tableInit + ", records = " + recordInit;
		} else {
			message = ex.toString();
		}
		if (useTable) {
			recordResult(message);
		} else {
			log.info(message);
		}
	}

	public String getCharset() {
		return globalCharset;
	}

	public void setCharset(String charset) {
		this.globalCharset = charset;
	}
}
