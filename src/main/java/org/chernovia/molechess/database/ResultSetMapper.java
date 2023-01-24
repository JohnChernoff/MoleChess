package org.chernovia.molechess.database;

import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@FunctionalInterface
public interface ResultSetMapper<R> {
    @NotNull Optional<R> map(@NotNull ResultSet resultSet) throws SQLException;
}
