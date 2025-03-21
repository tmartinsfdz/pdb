/*
 * Copyright 2014 Feedzai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.feedzai.commons.sql.abstraction.engine.impl;

import com.feedzai.commons.sql.abstraction.ddl.DbColumn;
import com.feedzai.commons.sql.abstraction.ddl.DbColumnConstraint;
import com.feedzai.commons.sql.abstraction.ddl.DbColumnType;
import com.feedzai.commons.sql.abstraction.ddl.DbEntity;
import com.feedzai.commons.sql.abstraction.ddl.DbFk;
import com.feedzai.commons.sql.abstraction.ddl.DbIndex;
import com.feedzai.commons.sql.abstraction.dml.dialect.Dialect;
import com.feedzai.commons.sql.abstraction.dml.result.PostgreSqlResultIterator;
import com.feedzai.commons.sql.abstraction.dml.result.ResultColumn;
import com.feedzai.commons.sql.abstraction.dml.result.ResultIterator;
import com.feedzai.commons.sql.abstraction.engine.AbstractDatabaseEngine;
import com.feedzai.commons.sql.abstraction.engine.AbstractTranslator;
import com.feedzai.commons.sql.abstraction.engine.ConnectionResetException;
import com.feedzai.commons.sql.abstraction.engine.DatabaseEngineDriver;
import com.feedzai.commons.sql.abstraction.engine.DatabaseEngineException;
import com.feedzai.commons.sql.abstraction.engine.MappedEntity;
import com.feedzai.commons.sql.abstraction.engine.configuration.PdbProperties;
import com.feedzai.commons.sql.abstraction.engine.handler.OperationFault;
import com.feedzai.commons.sql.abstraction.engine.handler.QueryExceptionHandler;
import com.feedzai.commons.sql.abstraction.engine.impl.postgresql.PostgresSqlQueryExceptionHandler;
import com.feedzai.commons.sql.abstraction.entry.EntityEntry;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.feedzai.commons.sql.abstraction.dml.dialect.SqlBuilder.column;
import static com.feedzai.commons.sql.abstraction.dml.dialect.SqlBuilder.max;
import static com.feedzai.commons.sql.abstraction.dml.dialect.SqlBuilder.select;
import static com.feedzai.commons.sql.abstraction.dml.dialect.SqlBuilder.table;
import static com.feedzai.commons.sql.abstraction.util.StringUtils.md5;
import static com.feedzai.commons.sql.abstraction.util.StringUtils.quotize;
import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.join;

/**
 * PostgreSQL specific database implementation.
 *
 * @author Rui Vilao (rui.vilao@feedzai.com)
 * @since 2.0.0
 */
public class PostgreSqlEngine extends AbstractDatabaseEngine {

    /**
     * The PostgreSQL JDBC driver.
     */
    protected static final String POSTGRESQL_DRIVER = DatabaseEngineDriver.POSTGRES.driver();
    /**
     * Name is already used by an existing object.
     */
    public static final String NAME_ALREADY_EXISTS = "42P07";
    /**
     * Table can have only one primary key.
     */
    public static final String TABLE_CAN_ONLY_HAVE_ONE_PRIMARY_KEY = "42P16";
    /**
     * Table or view does not exist.
     */
    public static final String TABLE_OR_VIEW_DOES_NOT_EXIST = "42P01";
    /**
     * Constraint name already exists.
     */
    public static final String CONSTRAINT_NAME_ALREADY_EXISTS = "42710";

    /**
     * The default SSL mode for the connection, when that PostgreSQL property is not set in the JDBC URL but SSL is
     * enabled.
     *
     * This is needed to avoid requiring configuration changes, by keeping the old behavior where if SSL was enabled
     * ("ssl" option present in JDBC URL, without arguments or with argument "true") but SSL mode was not set, then SSL
     * mode would work as "require".
     * The new behavior since driver v42.2.5 when SSL is enabled but SSL mode is not set is "verify-full".
     *
     * When using SSL it is recommended to perform the certificate verifications, but that should be explicitly set
     * (either setting SSL mode as "verify-full", or using an SSL connection factory that already does that).
     */
    private static final String LEGACY_DEFAULT_SSL_MODE = "require";

    /**
     * An instance of {@link QueryExceptionHandler} specific for PostgreSQL engine, to be used in disambiguating
     * SQL exceptions.
     * @since 2.5.1
     */
    public static final QueryExceptionHandler PG_QUERY_EXCEPTION_HANDLER = new PostgresSqlQueryExceptionHandler();

    /**
     * Creates a new PostgreSql connection.
     *
     * @param properties The properties for the database connection.
     * @throws DatabaseEngineException When the connection fails.
     */
    public PostgreSqlEngine(PdbProperties properties) throws DatabaseEngineException {
        this(properties, POSTGRESQL_DRIVER);
    }

    /**
     * Creates a new PostgreSql connection.
     *
     * Note: this constructor exists so that most of the code in this implementation can be reused
     * by (almost) compatible engines, like CockroachDB.
     *
     * @param properties The properties for the database connection.
     * @param driver     The driver to connect to the database.
     * @throws DatabaseEngineException When the connection fails.
     * @since 2.5.0
     */
    protected PostgreSqlEngine(final PdbProperties properties, final String driver) throws DatabaseEngineException {
        super(driver, properties, Dialect.POSTGRESQL);
    }

    @Override
    protected int entityToPreparedStatement(final DbEntity entity, final PreparedStatement ps, final EntityEntry entry, final boolean useAutoInc) throws DatabaseEngineException {

        int i = 1;
        for (DbColumn column : entity.getColumns()) {
            if (column.isAutoInc() && useAutoInc) {
                continue;
            }

            try {
                final Object val;
                if (column.isDefaultValueSet() && !entry.containsKey(column.getName())) {
                    val = column.getDefaultValue().getConstant();
                } else {
                    val = entry.get(column.getName());
                }

                switch (column.getDbColumnType()) {
                    case BLOB:
                        ps.setBytes(i, objectToArray(val));
                        break;

                    case CLOB:
                        if (val == null) {
                            ps.setNull(i, Types.CLOB);
                            break;
                        }

                        if (val instanceof String) {
                            //StringReader sr = new StringReader((String) val);
                            //ps.setClob(i, sr);
                            // postrgresql driver des not have setClob implemented
                            ps.setString(i, (String) val);
                        } else {
                            throw new DatabaseEngineException("Cannot convert " + val.getClass().getSimpleName() + " to String. CLOB columns only accept Strings.");
                        }
                        break;

                    case JSON:
                        ps.setObject(i, getJSONValue((String)val));
                        break;

                    default:
                        ps.setObject(i, val);
                }
            } catch (final Exception ex) {
                throw new DatabaseEngineException("Error while mapping variables to database", ex);
            }

            i++;
        }

        return i - 1;
    }

    @Override
    public synchronized void setParameter(final String name, final int index, Object param, DbColumnType paramType) throws DatabaseEngineException, ConnectionResetException {
        if (paramType == DbColumnType.JSON) {
            param = getJSONValue((String)param);
        }
        super.setParameter(name, index, param);
    }

    @Override
    public boolean isStringAggDistinctCapable() {
        return true;
    }

    /**
     * Converts a String value into a PG JSON value ready to be assigned to bind variables in Prepared Statements.
     *
     * @param val   The String representation of the JSON value.
     * @return      The correspondent JSON value
     * @throws DatabaseEngineException if there is an error creating the value. It should never be thrown.
     * @since 2.1.5
     */
    private Object getJSONValue(String val) throws DatabaseEngineException {
        try {
            PGobject dataObject = new PGobject();
            dataObject.setType("jsonb");
            dataObject.setValue(val);
            return dataObject;
        } catch (final SQLException ex) {
            throw new DatabaseEngineException("Error while mapping variables to database, value = " + val, ex);
        }
    }

    @Override
    protected void createTable(final DbEntity entity) throws DatabaseEngineException {

        List<String> createTable = new ArrayList<>();

        createTable.add("CREATE TABLE");
        createTable.add(quotize(entity.getName()));
        List<String> columns = new ArrayList<>();
        for (DbColumn c : entity.getColumns()) {
            List<String> column = new ArrayList<>();
            column.add(quotize(c.getName()));
            column.add(translateType(c));

            for (DbColumnConstraint cc : c.getColumnConstraints()) {
                column.add(cc.translate());
            }

            if (c.isDefaultValueSet()) {
                column.add("DEFAULT");
                column.add(translate(c.getDefaultValue()));
            }

            columns.add(join(column, " "));
        }
        createTable.add("(" + join(columns, ", ") + ")");

        final String createTableStatement = join(createTable, " ");

        logger.trace(createTableStatement);

        Statement s = null;
        try {
            s = conn.createStatement();
            s.executeUpdate(createTableStatement);
        } catch (final SQLException ex) {
            if (ex.getSQLState().startsWith(NAME_ALREADY_EXISTS)) {
                logger.debug(dev, "'{}' is already defined", entity.getName());
                handleOperation(new OperationFault(entity.getName(), OperationFault.Type.TABLE_ALREADY_EXISTS), ex);
            } else {
                throw new DatabaseEngineException("Something went wrong handling statement", ex);
            }
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (final Exception e) {
                logger.trace("Error closing statement.", e);
            }
        }
    }

    @Override
    protected void addPrimaryKey(final DbEntity entity) throws DatabaseEngineException {
        if (entity.getPkFields().size() == 0) {
            return;
        }

        List<String> pks = new ArrayList<>();
        for (String pk : entity.getPkFields()) {
            pks.add(quotize(pk));
        }

        final String pkName = md5(format("PK_%s", entity.getName()), properties.getMaxIdentifierSize());

        List<String> statement = new ArrayList<>();
        statement.add("ALTER TABLE");
        statement.add(quotize(entity.getName()));
        statement.add("ADD CONSTRAINT");
        statement.add(quotize(pkName));
        statement.add("PRIMARY KEY");
        statement.add("(" + join(pks, ", ") + ")");

        final String addPrimaryKey = join(statement, " ");

        logger.trace(addPrimaryKey);

        Statement s = null;
        try {
            s = conn.createStatement();
            s.executeUpdate(addPrimaryKey);
        } catch (final SQLException ex) {
            if (ex.getSQLState().startsWith(TABLE_CAN_ONLY_HAVE_ONE_PRIMARY_KEY)) {
                logger.debug(dev, "'{}' already has a primary key", entity.getName());
                handleOperation(new OperationFault(entity.getName(), OperationFault.Type.PRIMARY_KEY_ALREADY_EXISTS), ex);
            } else {
                throw new DatabaseEngineException("Something went wrong handling statement", ex);
            }
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (final Exception e) {
                logger.trace("Error closing statement.", e);
            }
        }
    }

    @Override
    protected void addIndexes(final DbEntity entity) throws DatabaseEngineException {
        List<DbIndex> indexes = entity.getIndexes();

        for (DbIndex index : indexes) {

            List<String> createIndex = new ArrayList<>();
            createIndex.add("CREATE");
            if (index.isUnique()) {
                createIndex.add("UNIQUE");
            }
            createIndex.add("INDEX");

            List<String> columns = new ArrayList<>();
            List<String> columnsForName = new ArrayList<>();
            for (String column : index.getColumns()) {
                columns.add(quotize(column));
                columnsForName.add(column);
            }
            final String idxName = md5(format("%s_%s_IDX", entity.getName(), join(columnsForName, "_")), properties.getMaxIdentifierSize());
            createIndex.add(quotize(idxName));
            createIndex.add("ON");
            createIndex.add(quotize(entity.getName()));
            createIndex.add("(" + join(columns, ", ") + ")");

            final String statement = join(createIndex, " ");

            logger.trace(statement);

            Statement s = null;
            try {
                s = conn.createStatement();
                s.executeUpdate(statement);
            } catch (final SQLException ex) {
                if (ex.getSQLState().startsWith(NAME_ALREADY_EXISTS)) {
                    logger.debug(dev, "'{}' is already defined", idxName);
                    handleOperation(new OperationFault(entity.getName(), OperationFault.Type.INDEX_ALREADY_EXISTS), ex);
                } else {
                    throw new DatabaseEngineException("Something went wrong handling statement", ex);
                }
            } finally {
                try {
                    if (s != null) {
                        s.close();
                    }
                } catch (final Exception e) {
                    logger.trace("Error closing statement.", e);
                }
            }
        }
    }

    @Override
    protected void addSequences(DbEntity entity) throws DatabaseEngineException {
        /*
         * Do nothing by default since we support
         * auto incrementation using the serial types.
         */
    }

    @Override
    protected MappedEntity createPreparedStatementForInserts(final DbEntity entity) throws DatabaseEngineException {

        List<String> insertInto = new ArrayList<>();
        insertInto.add("INSERT INTO");
        insertInto.add(quotize(entity.getName()));
        List<String> insertIntoWithAutoInc = new ArrayList<>();
        insertIntoWithAutoInc.add("INSERT INTO");
        insertIntoWithAutoInc.add(quotize(entity.getName()));
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<String> columnsWithAutoInc = new ArrayList<>();
        List<String> valuesWithAutoInc = new ArrayList<>();
        String returning = null;
        for (DbColumn column : entity.getColumns()) {
            columnsWithAutoInc.add(quotize(column.getName()));
            valuesWithAutoInc.add("?");
            if (column.isAutoInc()) {
                returning = column.getName();
            } else {
                columns.add(quotize(column.getName()));
                values.add("?");

            }
        }

        insertInto.add("(" + join(columns, ", ") + ")");
        insertInto.add("VALUES (" + join(values, ", ") + ")");

        insertIntoWithAutoInc.add("(" + join(columnsWithAutoInc, ", ") + ")");
        insertIntoWithAutoInc.add("VALUES (" + join(valuesWithAutoInc, ", ") + ")");

        List<String> insertIntoReturn = new ArrayList<>(insertInto);


        insertIntoReturn.add(format("RETURNING %s", returning == null ? "0" : quotize(returning)));
        insertIntoWithAutoInc.add(format("RETURNING %s", returning == null ? "0" : quotize(returning)));


        final String insertStatement = join(insertInto, " ");
        final String insertReturnStatement = join(insertIntoReturn, " ");
        final String statementWithAutoInt = join(insertIntoWithAutoInc, " ");

        logger.trace(insertStatement);
        logger.trace(insertReturnStatement);

        PreparedStatement ps, psReturn, psWithAutoInc;
        try {

            ps = conn.prepareStatement(insertStatement);
            psReturn = conn.prepareStatement(insertReturnStatement);
            psWithAutoInc = conn.prepareStatement(statementWithAutoInt);

            return new MappedEntity().setInsert(ps).setInsertReturning(psReturn).setInsertWithAutoInc(psWithAutoInc).setAutoIncColumn(returning);
        } catch (final SQLException ex) {
            throw new DatabaseEngineException("Something went wrong handling statement", ex);
        }
    }

    @Override
    protected void dropSequences(DbEntity entity) throws DatabaseEngineException {
        /*
         * Remember that we not support sequences in PostgreSql.
         * We're using SERIAL types instead.
         */
    }

    @Override
    protected void dropTable(DbEntity entity) throws DatabaseEngineException {
        Statement drop = null;
        try {
            drop = conn.createStatement();
            final String query = format("DROP TABLE %s CASCADE", quotize(entity.getName()));
            logger.trace(query);
            drop.executeUpdate(query);
        } catch (final SQLException ex) {
            if (ex.getSQLState().startsWith(TABLE_OR_VIEW_DOES_NOT_EXIST)) {
                logger.debug(dev, "Table '{}' does not exist", entity.getName());
                handleOperation(new OperationFault(entity.getName(), OperationFault.Type.TABLE_DOES_NOT_EXIST), ex);
            } else {
                throw new DatabaseEngineException("Error dropping table", ex);
            }
        } finally {
            try {
                if (drop != null) {
                    drop.close();
                }
            } catch (final Exception e) {
                logger.trace("Error closing statement.", e);
            }
        }
    }

    @Override
    protected void dropColumn(DbEntity entity, String... columns) throws DatabaseEngineException {
        Statement drop = null;

        List<String> removeColumns = new ArrayList<>();
        removeColumns.add("ALTER TABLE");
        removeColumns.add(quotize(entity.getName()));
        List<String> cols = new ArrayList<>();
        for (String col : columns) {
            cols.add("DROP COLUMN " + quotize(col));
        }
        removeColumns.add(join(cols, ","));

        try {
            drop = conn.createStatement();
            final String query = join(removeColumns, " ");
            logger.trace(query);
            drop.executeUpdate(query);
        } catch (final SQLException ex) {
            if (ex.getMessage().startsWith(TABLE_OR_VIEW_DOES_NOT_EXIST)) {
                logger.debug(dev, "Table '{}' does not exist", entity.getName());
            } else {
                throw new DatabaseEngineException("Error dropping column", ex);
            }
        } finally {
            try {
                if (drop != null) {
                    drop.close();
                }
            } catch (final Exception e) {
                logger.trace("Error closing statement.", e);
            }
        }

    }

    @Override
    protected void addColumn(DbEntity entity, DbColumn... columns) throws DatabaseEngineException {
        List<String> addColumns = new ArrayList<>();
        addColumns.add("ALTER TABLE");
        addColumns.add(quotize(entity.getName()));
        List<String> cols = new ArrayList<>();
        for (DbColumn c : columns) {
            List<String> column = new ArrayList<>();
            column.add("ADD COLUMN");
            column.add(quotize(c.getName()));
            column.add(translateType(c));

            for (DbColumnConstraint cc : c.getColumnConstraints()) {
                column.add(cc.translate());
            }

            if (c.isDefaultValueSet()) {
                column.add("DEFAULT");
                column.add(translate(c.getDefaultValue()));
            }

            cols.add(join(column, " "));
        }
        addColumns.add(join(cols, ","));
        final String addColumnsStatement = join(addColumns, " ");
        logger.trace(addColumnsStatement);

        Statement s = null;
        try {
            s = conn.createStatement();
            s.executeUpdate(addColumnsStatement);
        } catch (final SQLException ex) {
            throw new DatabaseEngineException("Something went wrong handling statement", ex);
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (final Exception e) {
                logger.trace("Error closing statement.", e);
            }
        }

    }

    @Override
    public Class<? extends AbstractTranslator> getTranslatorClass() {
        return PostgreSqlTranslator.class;
    }

    @Override
    protected synchronized long doPersist(final PreparedStatement ps,
                                          final MappedEntity me,
                                          final boolean useAutoInc,
                                          int lastBindPosition) throws Exception {
        try (final ResultSet generatedKeys = ps.executeQuery()) {

            long ret = 0;

            if (useAutoInc) {
                if (generatedKeys.next()) {
                    ret = generatedKeys.getLong(1);
                }
            } else if (hasIdentityColumn(me.getEntity())) {
                final List<Map<String, ResultColumn>> q = query(
                        select(max(column(me.getAutoIncColumn()))).from(table(me.getEntity().getName()))
                );
                if (!q.isEmpty()) {
                    ret = q.get(0).values().iterator().next().toLong();
                }

                updatePersistAutoIncSequence(me, ret);
            }

            return ret;
        }
    }

    /**
     * Updates the autoInc sequence value after a persist operation.
     *
     * @param mappedEntity      The mapped entity to for which to update the autoInc sequence.
     * @param currentAutoIncVal The current value for the autoInc column.
     * @since 2.5.1
     */
    protected void updatePersistAutoIncSequence(final MappedEntity mappedEntity, long currentAutoIncVal) {
        executeUpdateSilently(format(
                "ALTER SEQUENCE %s RESTART %d",
                quotize(mappedEntity.getEntity().getName() + "_" + mappedEntity.getAutoIncColumn() + "_seq"),
                currentAutoIncVal + 1
        ));
    }

    @Override
    protected void addFks(final DbEntity entity, Set<DbFk> fks) throws DatabaseEngineException {
        for (final DbFk fk : fks) {
            final List<String> quotizedLocalColumns = new ArrayList<>();
            for (String s : fk.getLocalColumns()) {
                quotizedLocalColumns.add(quotize(s));
            }

            final List<String> quotizedForeignColumns = new ArrayList<>();
            for (final String s : fk.getReferencedColumns()) {
                quotizedForeignColumns.add(quotize(s));
            }

            final String table = quotize(entity.getName());
            final String quotizedLocalColumnsSting = join(quotizedLocalColumns, ", ");
            final String quotizedForeignColumnsString = join(quotizedForeignColumns, ", ");

            final String alterTable =
                    format(
                            "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s (%s)",
                            table,
                            quotize(md5(
                                    "FK_" + table + quotizedLocalColumnsSting + quotizedForeignColumnsString,
                                    properties.getMaxIdentifierSize()
                            )),
                            quotizedLocalColumnsSting,
                            quotize(fk.getReferencedTable()),
                            quotizedForeignColumnsString
                    );

            Statement alterTableStmt = null;
            try {
                alterTableStmt = conn.createStatement();
                logger.trace(alterTable);
                alterTableStmt.executeUpdate(alterTable);
            } catch (final SQLException ex) {
                if (ex.getSQLState().equals(CONSTRAINT_NAME_ALREADY_EXISTS)) {
                    logger.debug(dev, "Foreign key for table '{}' already exists. Error code: {}.", entity.getName(), ex.getSQLState());
                } else {
                    throw new DatabaseEngineException(format(
                            "Could not add Foreign Key to entity %s. Error code: %s.",
                            entity.getName(),
                            ex.getSQLState()
                    ), ex);
                }
            } finally {
                try {
                    if (alterTableStmt != null) {
                        alterTableStmt.close();
                    }
                } catch (final Exception e) {
                    logger.trace("Error closing statement.", e);
                }
            }
        }
    }

    @Override
    protected Properties getDBProperties() {
        final Properties props = super.getDBProperties();

        /*
         Define login timeout for Postgres (in seconds);
         DriverManager.setLoginTimeout is supported by PostgreSQL driver, but is not working due to bug
            https://github.com/pgjdbc/pgjdbc/issues/879
         */
        final int loginTimeout = this.properties.getLoginTimeout();
        props.setProperty(PGProperty.LOGIN_TIMEOUT.getName(), Integer.toString(loginTimeout));

        final Properties parsedProps = Driver.parseURL(this.properties.getJdbc(), null);
        if (parsedProps != null) {
            /*
             If SSL is enabled ("ssl" option is present in JDBC URL, with argument "true" or without arguments)
             but SSL mode ("sslmode" option) is not set, use legacy behavior - consider "sslmode" = "require".
             NOTE: the properties should be explicitly set, this code may be reverted in the future
             */
            final boolean sslEnabled = PGProperty.SSL.getBoolean(parsedProps) || "".equals(PGProperty.SSL.get(parsedProps));
            if (sslEnabled && !PGProperty.SSL_MODE.isPresent(parsedProps)) {
                logger.trace("SSL enabled without SSL mode specified: \"{}\" will be used by default", LEGACY_DEFAULT_SSL_MODE);
                PGProperty.SSL_MODE.set(props, LEGACY_DEFAULT_SSL_MODE);
            }
        }

        return props;
    }

    @Override
    protected boolean checkConnection(final Connection conn) {
        final int timeout = this.properties.getCheckConnectionTimeout();
        final int socketTimeout;
        try {
            socketTimeout = conn.getNetworkTimeout();
            try {
                // Set the socket timeout to verify the connection.
                conn.setNetworkTimeout(socketTimeoutExecutor, timeout * 1000);
                return pingConnection(conn);
            } catch (final Exception ex) {
                logger.debug("It wasn't possible to verify the connection state within the timeout of {} seconds.",
                        timeout,
                        ex);
                return false;
            } finally {
                // Make sure to respect it afterwards.
                conn.setNetworkTimeout(socketTimeoutExecutor, socketTimeout);
            }
        } catch (final Exception ex) {
            logger.debug("It wasn't possible to reset the connection. Connection might be closed.");

            try {
                conn.close();
            } catch (final Exception e) {
                logger.debug("Error closing the connection.", e);
            }

            return false;
        }
    }

    /**
     * Executes a dummy query to verify if the connection is alive.
     *
     * @param conn The connection to test.
     * @return {@code true} if the connection is valid, {@code false} otherwise.
     */
    private boolean pingConnection(final Connection conn) {
        Statement s = null;
        try {
            s = conn.createStatement();
            s.executeQuery("select 1");

            return true;
        } catch (final SQLException e) {
            logger.debug("Connection is down.", e);
            return false;
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (final Exception e) {
                logger.trace("Error closing statement.", e);
            }
        }
    }

    @Override
    protected void setSchema(final String schema) throws DatabaseEngineException {
        super.setSchema(schema);

        final boolean isSchemaSet;
        try {
            isSchemaSet = this.conn.getSchema() != null;
        } catch (final Exception e) {
            throw new DatabaseEngineException(String.format("Could not set current schema to '%s'", schema), e);
        }

        if (!isSchemaSet) {
            throw new DatabaseEngineException(String.format("Schema '%s' doesn't exist", schema));
        }
    }

    @Override
    protected DbColumnType toPdbType(final int type, final String typeName) {
        DbColumnType pdbType = super.toPdbType(type, typeName);
        if (pdbType == DbColumnType.UNMAPPED && typeName.equals("jsonb")) {
            return DbColumnType.JSON;
        }
        return pdbType;
    }

    @Override
    protected ResultIterator createResultIterator(Statement statement, String sql) throws DatabaseEngineException {
        return new PostgreSqlResultIterator(statement, sql);
    }

    @Override
    protected ResultIterator createResultIterator(PreparedStatement ps) throws DatabaseEngineException {
        return new PostgreSqlResultIterator(ps);
    }

    @Override
    protected QueryExceptionHandler getQueryExceptionHandler() {
        return PG_QUERY_EXCEPTION_HANDLER;
    }
}
