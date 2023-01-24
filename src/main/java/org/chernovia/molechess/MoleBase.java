package org.chernovia.molechess;

import org.chernovia.molechess.database.ResultSetMapper;
import org.chernovia.molechess.database.StatementInitializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class MoleBase {
    static final class MoleQuery {
        private final @NotNull String statement;
        private final @Nullable PreparedStatement preparedStatement;
        private final @Nullable ResultSet resultSet;
        private final @NotNull Connection conn;

        private MoleQuery(final @NotNull String statement, final @NotNull Connection conn) {
            this.statement = statement;
            this.conn = conn;
            this.preparedStatement = null;
            this.resultSet = null;
        }

        private MoleQuery(final @NotNull String statement, final @NotNull PreparedStatement preparedStatement,
                          final @NotNull ResultSet resultSet, final @NotNull Connection conn) {
            this.statement = statement;
            this.resultSet = resultSet;
            this.preparedStatement = preparedStatement;
            this.conn = conn;
        }

        private @NotNull Optional<MoleQuery> withResultSet(final @NotNull StatementInitializer init) {
            return prepareStatement(init).flatMap(s ->
                    runQuery(s).flatMap(q ->
                            Optional.of(new MoleQuery(this.statement, s, q, this.conn))));
        }

        private @NotNull Optional<ResultSet> getResultSet() {
            return Optional.ofNullable(this.resultSet);
        }

        public <R> @NotNull Optional<R> mapResultSet(final @NotNull StatementInitializer init, final @NotNull ResultSetMapper<R> mapper) {
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

        private @NotNull Optional<PreparedStatement> prepareStatement(final @NotNull StatementInitializer init) {
            try {
                final @Nullable PreparedStatement preparedStatement = conn.prepareStatement(statement);
                if (preparedStatement != null) {
                    init.setVariables(preparedStatement);
                }
                return Optional.ofNullable(preparedStatement);
            } catch (final @NotNull SQLException e) {
                logSQLException(e);
                cleanup();
                return Optional.empty();
            }
        }

        private @NotNull Optional<ResultSet> runQuery(final @NotNull PreparedStatement statement) {
            try {
                return Optional.of(statement.executeQuery());
            } catch (final @NotNull SQLException e) {
                logSQLException(e);
                cleanup();
                return Optional.empty();
            }
        }

        public void runUpdate(final @NotNull StatementInitializer init, final @NotNull Consumer<SQLException> whenFails) {
            try (final @Nullable PreparedStatement preparedStatement = conn.prepareStatement(statement)) {
                if (preparedStatement != null) {
                    init.setVariables(preparedStatement);
                    preparedStatement.executeUpdate();
                }
            } catch (final @NotNull SQLException e) {
                whenFails.accept(e);
            }
        }

        public void runUpdate(final @NotNull StatementInitializer varSetter) {
            runUpdate(varSetter, MoleBase::logSQLException);
        }

        public void runUpdate() {
            runUpdate(it -> {
            }, MoleBase::logSQLException);
        }

        private void cleanup() {
            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (final @NotNull SQLException e) {
                    logSQLException(e);
                }
            }
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (final @NotNull SQLException e) {
                    logSQLException(e);
                }
            }
        }
    }

    private static final class Credentials {
        private final @NotNull String uri;
        private final @NotNull String usr;
        private final @NotNull String pwd;
        private final @NotNull String db;

        private Credentials(final @NotNull String uri, final @NotNull String usr, final @NotNull String pwd, final @NotNull String db) {
            this.uri = uri;
            this.usr = usr;
            this.pwd = pwd;
            this.db = db;
        }
    }

    private @Nullable Connection conn;
    private final @Nullable Credentials credentials;

    public MoleBase(final @NotNull String uri, final @NotNull String usr, final @NotNull String pwd, final @NotNull String db) {
        credentials = new Credentials(uri, usr, pwd, db);
        conn = connect(credentials);
    }

    public MoleBase() {
        credentials = null;
        conn = null;
    }

    private @Nullable Connection connect(final @NotNull Credentials credentials) {
        try {
            String connStr = "jdbc:mysql://" + credentials.uri +
                    "/" + credentials.db +
                    "?user=" + credentials.usr +
                    "&password=" + credentials.pwd;
            return DriverManager.getConnection(connStr);
        } catch (SQLException ex) {
            logSQLException(ex);
            return null;
        }
    }

    private @NotNull Optional<Connection> getConn() {
        try {
            if (credentials != null && conn != null && conn.isClosed()) {
                conn = connect(credentials);
            }
        } catch (final @NotNull SQLException e) {
            logSQLException(e);
            conn = null;
        }
        return Optional.ofNullable(conn);
    }

    public @NotNull Optional<MoleQuery> makeQuery(final @NotNull String queryStr) {
        return getConn().flatMap(conn -> Optional.of(new MoleQuery(queryStr, conn)));
    }

    private static void logSQLException(final @NotNull SQLException e) {
        MoleServ.log(Level.SEVERE, "SQLException: " + e.getMessage());
        MoleServ.log(Level.SEVERE, "SQLState: " + e.getSQLState());
        MoleServ.log(Level.SEVERE, "VendorError: " + e.getErrorCode());
    }

}
