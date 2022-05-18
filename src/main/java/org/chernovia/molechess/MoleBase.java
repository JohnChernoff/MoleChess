package org.chernovia.molechess;

import org.chernovia.molechess.database.ResultSetMapper;
import org.chernovia.molechess.database.StatementInitializer;

import java.sql.*;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

public class MoleBase {
    static class MoleQuery {
        private final String statement;
        private final PreparedStatement preparedStatement;
        private final ResultSet resultSet;
        private final Connection conn;

        public MoleQuery(final String statement, final Connection conn) {
            this.statement = statement;
            this.conn = conn;
            this.preparedStatement = null;
            this.resultSet = null;
        }

        private MoleQuery(final String statement, final PreparedStatement preparedStatement, final ResultSet resultSet,
                          final Connection conn) {
            this.statement = statement;
            this.resultSet = resultSet;
            this.preparedStatement = preparedStatement;
            this.conn = conn;
        }

        private Optional<MoleQuery> withResultSet(final StatementInitializer init) {
            return prepareStatement(init).flatMap(s ->
                    runQuery(s).flatMap(q ->
                            Optional.of(new MoleQuery(this.statement, s, q, this.conn))));
        }

        private Optional<ResultSet> getResultSet() {
            return Optional.ofNullable(this.resultSet);
        }

        public <R> Optional<R> mapResultSet(final StatementInitializer init, final ResultSetMapper<R> mapper) {
            return withResultSet(init).flatMap(it -> it.getResultSet().flatMap(rs -> {
                try {
                    return mapper.map(rs);
                } catch (SQLException ex) {
                    logSQLException(ex);
                    return Optional.empty();
                } finally {
                    it.cleanup();
                }
            }));
        }

        private Optional<PreparedStatement> prepareStatement(final StatementInitializer init) {
            try {
                final PreparedStatement preparedStatement = conn.prepareStatement(statement);
                init.setVariables(preparedStatement);
                return Optional.of(preparedStatement);
            } catch (SQLException e) {
                logSQLException(e);
                cleanup();
                return Optional.empty();
            }
        }

        private Optional<ResultSet> runQuery(final PreparedStatement statement) {
            try {
                return Optional.of(statement.executeQuery());
            } catch (SQLException e) {
                logSQLException(e);
                cleanup();
                return Optional.empty();
            }
        }

        public void runUpdate(final StatementInitializer varSetter, final Consumer<SQLException> whenFails) {
            try (final PreparedStatement preparedStatement = conn.prepareStatement(statement)) {
                varSetter.setVariables(preparedStatement);
                preparedStatement.executeUpdate();
            } catch (SQLException e) {
                whenFails.accept(e);
            }
        }

        public void runUpdate(final StatementInitializer varSetter) {
            runUpdate(varSetter, MoleBase::logSQLException);
        }

        private void cleanup() {
            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    logSQLException(e);
                }
            }
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    logSQLException(e);
                }
            }
        }
    }

    private final Connection conn;

    public MoleBase(String uri, String usr, String pwd, String db) {
        conn = connect(uri, usr, pwd, db);
    }

    private Connection connect(String uri, String usr, String pwd, String db) {
        try {
            String connStr = "jdbc:mysql://" + uri +
                    "/" + db +
                    "?user=" + usr +
                    "&password=" + pwd;
            return DriverManager.getConnection(connStr);
        } catch (SQLException ex) {
            logSQLException(ex);
            return null;
        }
    }

    private Optional<Connection> getConn() {
        return Optional.ofNullable(conn);
    }

    public Optional<MoleQuery> makeQuery(final String queryStr) {
        return getConn().flatMap(conn -> Optional.of(new MoleQuery(queryStr, conn)));
    }

    private static void logSQLException(SQLException e) {
        MoleServ.log(Level.SEVERE, "SQLException: " + e.getMessage());
        MoleServ.log(Level.SEVERE, "SQLState: " + e.getSQLState());
        MoleServ.log(Level.SEVERE, "VendorError: " + e.getErrorCode());
    }

}
