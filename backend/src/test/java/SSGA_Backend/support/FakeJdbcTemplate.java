package SSGA_Backend.support;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Minimal JdbcTemplate stand-in for unit tests. Answers are registered by a
 * substring of the executed SQL, so a test only has to describe the parts of
 * the statement it cares about. Every executed statement is recorded, which is
 * how the tests assert that a service talks to the database it is supposed to
 * talk to.
 */
public class FakeJdbcTemplate extends JdbcTemplate {

    private final Map<String, Object> answers = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    private final List<String> executed = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private int updateRows = 1;
    private RuntimeException failure;
    private String failureOn;

    public FakeJdbcTemplate answer(String sqlFragment, Object value) {
        answers.put(sqlFragment, value);
        return this;
    }

    public FakeJdbcTemplate row(String sqlFragment, Map<String, Object> row) {
        rows.put(sqlFragment, row);
        return this;
    }

    public FakeJdbcTemplate updateRows(int updateRows) {
        this.updateRows = updateRows;
        return this;
    }

    public FakeJdbcTemplate failOn(String sqlFragment, RuntimeException failure) {
        this.failureOn = sqlFragment;
        this.failure = failure;
        return this;
    }

    public List<String> executed() {
        return executed;
    }

    /** Every statement with its bound arguments, so a test can assert on values. */
    public List<String> calls() {
        return calls;
    }

    public boolean executedSqlContaining(String fragment) {
        return executed.stream().anyMatch(sql -> sql.contains(fragment));
    }

    public boolean calledWith(String fragment) {
        return calls.stream().anyMatch(call -> call.contains(fragment));
    }

    @Override
    public int update(String sql, Object... args) {
        executed.add(sql);
        calls.add(sql + " " + java.util.Arrays.toString(args));
        raise(sql);
        return updateRows;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        executed.add(sql);
        calls.add(sql + " " + java.util.Arrays.toString(args));
        raise(sql);

        for (Map.Entry<String, Object> entry : answers.entrySet()) {
            if (sql.contains(entry.getKey())) {
                return requiredType.cast(entry.getValue());
            }
        }

        return null;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType) {
        executed.add(sql);
        calls.add(sql + " []");
        raise(sql);

        for (Map.Entry<String, Object> entry : answers.entrySet()) {
            if (sql.contains(entry.getKey())) {
                return requiredType.cast(entry.getValue());
            }
        }

        return null;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        executed.add(sql);
        calls.add(sql + " " + java.util.Arrays.toString(args));
        raise(sql);

        for (Map.Entry<String, Map<String, Object>> entry : rows.entrySet()) {
            if (sql.contains(entry.getKey())) {
                try {
                    return List.of(rowMapper.mapRow(resultSet(entry.getValue()), 0));
                } catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }

        return List.of();
    }

    private void raise(String sql) {
        if (failure != null && sql.contains(failureOn)) {
            throw failure;
        }
    }

    private static ResultSet resultSet(Map<String, Object> row) {
        return (ResultSet) Proxy.newProxyInstance(
                FakeJdbcTemplate.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    if ("getObject".equals(name) && args != null && args.length == 2) {
                        return row.get(String.valueOf(args[0]));
                    }

                    if (name.startsWith("get") && args != null && args.length == 1) {
                        Object value = row.get(String.valueOf(args[0]));
                        return value;
                    }

                    switch (name) {
                        case "wasNull":
                            return false;
                        case "next":
                            return true;
                        default:
                            return null;
                    }
                }
        );
    }
}
