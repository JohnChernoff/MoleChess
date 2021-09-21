package org.chernovia.molechess;

import java.sql.*;
import java.util.logging.Level;

public class MoleBase {
	class MoleQuery {
		private Statement statement = null;
		private ResultSet resultSet = null;
		private String queryString;
		private Connection conn;
		
		public MoleQuery(String qstr, Connection c) {
			queryString = qstr; conn = c;
		}
		
		public ResultSet runQuery() {
			try {
			    statement = conn.createStatement();
			    resultSet = statement.executeQuery(queryString);
			    return resultSet;
			}
			catch (SQLException ex) { oops(ex);	}
			return null;
		}
		
		public int runUpdate() {
			int status = 0;
			try {
			    statement = conn.createStatement();
			    status = statement.executeUpdate(queryString);
			}
			catch (SQLException ex) { oops(ex); }
		    cleanup();
			return status;
		}
		
		public void oops(SQLException e) {
			MoleServ.log(Level.SEVERE,"SQLException: " + e.getMessage());
			MoleServ.log(Level.SEVERE,"SQLState: " + e.getSQLState());
			MoleServ.log(Level.SEVERE,"VendorError: " + e.getErrorCode());
		}
		
		public void setQueryString(String str) { queryString = str; }
		public ResultSet getRS() { return resultSet; }
		public void cleanup() {
		    if (resultSet != null) { try { resultSet.close(); } catch (SQLException sqlEx) { } // ignore
		        resultSet = null;
		    }
		    if (statement != null) { try { statement.close(); } catch (SQLException sqlEx) { } // ignore
		        statement = null;
		    }
		}
	}
	
	Connection conn = null;
	
	public MoleBase(String uri,String usr,String pwd,String db) {
		conn = connect(uri,usr,pwd,db);
	}
	
	public Connection connect(String uri,String usr,String pwd,String db) {
		try {
			//Class.forName("com.mysql.jdbc.Driver"); //.newInstance();
			String connStr = "jdbc:mysql://" + uri + 
			   		"/" + db + 
			   		"?user=" + usr + 
			   		"&password=" + pwd;
			//System.out.println("Connection String: " + connStr);
			return DriverManager.getConnection(connStr);
		}
		//catch(ClassNotFoundException ex) {
		//   MoleServ.log(Level.SEVERE,"Error: unable to load driver class"); //System.exit(1);
		//}
		catch (SQLException ex) {
			MoleServ.log(Level.SEVERE,"SQLException: " + ex.getMessage());
			MoleServ.log(Level.SEVERE,"SQLState: " + ex.getSQLState());
			MoleServ.log(Level.SEVERE,"VendorError: " + ex.getErrorCode());
		}
		return null;
	}
	
	public Connection getConn() { return conn; }
	
	public MoleQuery makeQuery(String queryStr) {
		return new MoleQuery(queryStr,conn);
	}
	
}
