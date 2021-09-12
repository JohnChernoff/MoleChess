package org.chernovia.molechess;

public class MoleResult {
  boolean success;
  
  String message;
  
  public MoleResult(String s) {
    this.success = true;
    this.message = s;
  }
  
  public MoleResult(boolean b, String s) {
    this.success = b;
    this.message = s;
  }
}
