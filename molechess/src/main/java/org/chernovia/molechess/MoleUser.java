package org.chernovia.molechess;

import org.chernovia.lib.zugserv.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class MoleUser {
  String oauth;
  String name;
  private Connection conn;
  
  public MoleUser(Connection c, String o, String n) {
    this.conn = c;
    this.oauth = o;
    this.name = n;
  }
  
  public boolean sameConnection(Connection c) {
    return (this.conn == c);
  }
  
  public void setConn(Connection c) { conn = c; }
  
  public void tell(String msg) {
    tell("serv_msg", msg);
  }
  
  public void tell(String type, String msg) {
    ObjectNode node = MoleServ.mapper.createObjectNode();
    node.put("msg", msg);
    tell(type, (JsonNode)node);
  }
  
  public void tell(String type, JsonNode node) {
    if (this.conn != null)
      this.conn.tell(type, node); 
  }
  
  public JsonNode toJSON() {
    ObjectNode obj = MoleServ.mapper.createObjectNode();
    obj.put("name", this.name);
    return (JsonNode)obj;
  }
  
  public boolean equals(Object o) {
	  if (o == this) return true;
	  if (!(o instanceof MoleUser)) return false;
	  return ((MoleUser)o).oauth.equals(this.oauth);
  }
}
