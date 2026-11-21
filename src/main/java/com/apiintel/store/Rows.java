package com.apiintel.store;

import java.util.Map;

/** Case-tolerant access to JdbcTemplate queryForList rows (SQLite lowercase vs H2 uppercase). */
public final class Rows {
  private Rows() {}

  public static Object get(Map<String, Object> row, String column) {
    Object v = row.get(column);
    if (v == null) v = row.get(column.toUpperCase());
    if (v == null) v = row.get(column.toLowerCase());
    return v;
  }

  public static String str(Map<String, Object> row, String column) {
    Object v = get(row, column);
    return v == null ? null : String.valueOf(v);
  }

  public static int num(Map<String, Object> row, String column) {
    Object v = get(row, column);
    return v instanceof Number n ? n.intValue() : 0;
  }
}
