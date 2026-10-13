package com.softitrend.office.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.core.env.Environment;

/** Server configuration is a trust boundary; diagnostics never contain its values. */
public record ConnectionSettings(URI baseUrl,String secret,Duration timeout) {
 public static ConnectionSettings read(Environment env) {
  String raw=env.getProperty("office.upstream.base-url", "");
  String secret=env.getProperty("office.upstream.secret", "");
  int millis=env.getProperty("office.upstream.timeout-ms",Integer.class,3000);
  if(millis<100||millis>30000) throw invalid();
  URI uri=null;
  if(!raw.isBlank()) {
   try { uri=URI.create(raw); } catch(IllegalArgumentException e) { throw invalid(); }
   boolean local=env.matchesProfiles("dev","test");
   boolean scheme="https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()) && loopback(uri.getHost()));
   if(!scheme||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
     || !(uri.getRawPath().isEmpty()||uri.getRawPath().equals("/"))||uri.getPort()==0||uri.getPort()>65535) throw invalid();
  }
  if(secret.length()>4096||secret.chars().anyMatch(Character::isISOControl)) throw invalid();
  return new ConnectionSettings(uri,secret,Duration.ofMillis(millis));
 }
 public boolean enabled() { return baseUrl!=null&&!secret.isBlank(); }
 public static boolean loopback(String host) { return "127.0.0.1".equals(host)||"localhost".equals(host)||"::1".equals(host)||"[::1]".equals(host); }
 private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid Feezit connection configuration"); }
}
