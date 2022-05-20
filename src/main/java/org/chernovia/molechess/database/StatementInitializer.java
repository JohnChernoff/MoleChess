package org.chernovia.molechess.database;

import java.sql.PreparedStatement;
import java.sql.SQLException;

@FunctionalInterface
public interface StatementInitializer {
    public void setVariables(PreparedStatement statement) throws SQLException;
}
