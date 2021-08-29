package org.chernovia.molechess;

public class MoleResult {
  boolean result;
  
  String message;
  
  public MoleResult(String s) {
    this.result = true;
    this.message = s;
  }
  
  public MoleResult(boolean b, String s) {
    this.result = b;
    this.message = s;
  }
}
