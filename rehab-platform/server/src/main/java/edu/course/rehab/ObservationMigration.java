package edu.course.rehab;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;

/** Additive migration: retain all existing plans and simulated reports. */
@Component
@Order(-10)
public class ObservationMigration implements CommandLineRunner {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    public ObservationMigration(DataSource source) { this.source=source;this.jdbc=new JdbcTemplate(source); }
    public void run(String...args) throws Exception {
        try(Connection c=source.getConnection()) {
            boolean mysql=c.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
            for(String column:new String[]{"cycle_min_seconds","cycle_max_seconds"}) {
                try(ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,"plan_item",column)) {
                    if(!r.next())jdbc.execute("ALTER TABLE plan_item ADD COLUMN "+column+" DOUBLE NULL");
                }
            }
            String[][] columns={{"repetition_result","amplitude_ok","BOOLEAN"},{"repetition_result","tempo_ok","BOOLEAN"},{"repetition_result","trunk_ok","BOOLEAN"},{"repetition_result","hold_ok","BOOLEAN"},{"feature_sample","angle","DOUBLE"}};
            for(String[] x:columns) {
                try(ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,x[0],x[1])) {
                    if(r.next() && r.getInt("NULLABLE")==DatabaseMetaData.columnNoNulls)
                        jdbc.execute("ALTER TABLE "+x[0]+(mysql?" MODIFY COLUMN "+x[1]+" "+x[2]+" NULL":" ALTER COLUMN "+x[1]+" DROP NOT NULL"));
                }
            }
        }
    }
}
