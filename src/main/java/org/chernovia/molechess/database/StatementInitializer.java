package org.chernovia.molechess.database;

import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.SQLException;

@FunctionalInterface
public interface StatementInitializer {
    void setVariables(@NotNull PreparedStatement statement) throws SQLException;
}
