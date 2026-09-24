// Diagnostic probes. Only use the disposable databases described in docs/review.md.
import java.sql.*;
import java.net.*;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.*;

class DatabaseProbes {
    static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/"+database,"postgres","");
    }
    public static void main(String[] args) throws Exception {
        String username="review-"+UUID.randomUUID().toString().substring(0,8);
        String hash;
        try(var db=connect("postgres");var s=db.createStatement();var r=s.executeQuery("SELECT password_hash FROM staff WHERE username='alex'")) {
            if(!r.next())throw new IllegalStateException("Run the existing integration fixture first");hash=r.getString(1);
        }
        try(var db=connect("queuelive_demo")) {
            try(var s=db.prepareStatement("INSERT INTO staff(username,password_hash) VALUES (?,?)")){s.setString(1,username);s.setString(2,hash);s.executeUpdate();}
            try {
                var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
                var client=HttpClient.newBuilder().cookieHandler(cookies).build();
                String origin="http://127.0.0.1:8080/api";
                var bootstrap=client.send(HttpRequest.newBuilder(URI.create(origin+"/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
                String csrf=bootstrap.body().split("\\\"csrfToken\\\":\\\"")[1].split("\\\"")[0];
                var login=client.send(HttpRequest.newBuilder(URI.create(origin+"/login")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password=test-password-123")).build(),HttpResponse.BodyHandlers.ofString());
                if(login.statusCode()!=204)throw new IllegalStateException("Probe login failed: "+login.statusCode());
                try(var s=db.prepareStatement("UPDATE staff SET enabled=false WHERE username=?")){s.setString(1,username);s.executeUpdate();}
                var access=client.send(HttpRequest.newBuilder(URI.create(origin+"/staff")).GET().build(),HttpResponse.BodyHandlers.discarding());
                System.out.println("Disabled staff with existing session: HTTP "+access.statusCode());
                if(access.statusCode()!=200)throw new IllegalStateException("Behavior changed; revise finding");
            } finally {
                try(var s=db.prepareStatement("DELETE FROM spring_session WHERE principal_name=?")){s.setString(1,username);s.executeUpdate();}
                try(var s=db.prepareStatement("DELETE FROM staff WHERE username=?")){s.setString(1,username);s.executeUpdate();}
            }
        }
        // Establish B's snapshot before A commits; no arbitrary sleeps.
        try(var a=connect("postgres");var b=connect("postgres")) {
            a.setAutoCommit(false);b.setAutoCommit(false);b.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try(var s=a.createStatement()){s.executeUpdate("UPDATE staff SET last_seen=now() WHERE username='alex'");}
            try(var s=b.createStatement();var r=s.executeQuery("SELECT last_seen FROM staff WHERE username='alex'")){r.next();}
            a.commit();
            try(var s=b.createStatement()) {
                s.executeUpdate("UPDATE staff SET last_seen=now() WHERE username='alex'");
                throw new AssertionError("Expected a stale-snapshot conflict");
            } catch(SQLException ex) {
                System.out.println("Overlapping staff presence snapshots: SQLSTATE "+ex.getSQLState());
                if(!"40001".equals(ex.getSQLState()))throw ex;
            } finally {b.rollback();}
        }
    }
}
