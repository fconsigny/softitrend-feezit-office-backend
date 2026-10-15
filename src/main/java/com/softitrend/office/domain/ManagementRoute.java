package com.softitrend.office.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Only these capabilities can reach Feezit. Raw path is never normalized. */
public record ManagementRoute(String method, String suffix) {
 private static final String PREFIX="/office-api/management/";
 private static final String UUID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
 private record Capability(Set<String> methods, Pattern suffix) {}
 private static Capability cap(String methods,String path) { return new Capability(Set.of(methods.split(",")),Pattern.compile(path)); }
 private static final List<Capability> CAPABILITIES=List.of(
  cap("GET","brands"),cap("PUT","brands/aliases"),cap("POST","reservations"),
  cap("GET,POST","waitlist"),cap("GET,POST","waitlist/responses"),
  cap("GET","promos/"+UUID),cap("PUT","promos/"+UUID+"/content"),
  cap("GET,PUT","promos/"+UUID+"/banner"),
  cap("PUT","promos/"+UUID+"/description"),
  cap("PUT","promos/"+UUID+"/destination"),
  cap("GET","promos/"+UUID+"/participation"),
  cap("POST","promos/"+UUID+"/(reports|fraud-removal)"),cap("PUT","promos/"+UUID+"/resolve"),
  cap("GET","accounts/"+UUID+"/stats"),cap("GET,PUT,DELETE","accounts/"+UUID+"/moderation"),
  cap("PUT,DELETE","accounts/"+UUID+"/ban"),cap("POST","accounts/"+UUID+"/profile"),
  cap("GET","outbox"),cap("PUT","outbox/"+UUID+"/sent"),cap("GET","email-deliveries"),
  cap("GET","outbox-page"),cap("GET","operations-status"),
  cap("POST","jobs/run"),cap("GET","accounts|promos|incidents"));
 public static boolean allowed(String method,String rawPath) {
  if(!rawPath.startsWith(PREFIX)) return false;
  String suffix=rawPath.substring(PREFIX.length());
  return CAPABILITIES.stream().anyMatch(c -> c.methods.contains(method) && c.suffix.matcher(suffix).matches());
 }
 public static ManagementRoute from(String method,String rawPath) {
  if(!allowed(method,rawPath)) throw new IllegalArgumentException("Unsupported operation");
  return new ManagementRoute(method,rawPath.substring(PREFIX.length()));
 }
 public boolean isBanner() {
  return (method.equals("GET")||method.equals("PUT")) && suffix.matches("promos/"+UUID+"/banner");
 }
 public void validateQuery(Map<String,List<String>> query) {
  Set<String> keys=Set.of();
  if(method.equals("GET")) {
   if(suffix.equals("waitlist")||suffix.equals("waitlist/responses")) keys=Set.of("store");
   else if(suffix.endsWith("/stats")) keys=Set.of("from","to");
   else if(suffix.equals("accounts")||suffix.equals("promos")) keys=Set.of("page","size","q");
   else if(suffix.equals("incidents")||suffix.equals("outbox-page")) keys=Set.of("page","size");
  }
  for(var e:query.entrySet()) {
   if(!keys.contains(e.getKey())||e.getValue().size()!=1) throw new IllegalArgumentException("Invalid query");
   String value=e.getValue().getFirst();
   if(value.length()>300||value.chars().anyMatch(c -> Character.isISOControl(c))) throw new IllegalArgumentException("Invalid query");
   switch(e.getKey()) {
    case "page" -> boundedInteger(value,0,10000);
    case "size" -> boundedInteger(value,1,100);
    case "from", "to" -> { if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||LocalDate.parse(value).getYear()<1) throw new IllegalArgumentException("Invalid date"); }
    case "q" -> { if(value.length()>200) throw new IllegalArgumentException("Invalid query"); }
    case "store" -> { if(value.isBlank()||value.length()>100) throw new IllegalArgumentException("Invalid store"); }
    default -> { }
   }
  }
  if(keys.contains("store")&&!query.containsKey("store")) throw new IllegalArgumentException("Store required");
 }
 private static void boundedInteger(String value,int min,int max) {
  if(!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid pagination");
  int n=Integer.parseInt(value); if(n<min||n>max) throw new IllegalArgumentException("Invalid pagination");
 }
}
