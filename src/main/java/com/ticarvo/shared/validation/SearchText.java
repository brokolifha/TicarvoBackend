package com.ticarvo.shared.validation;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
public final class SearchText {
 private SearchText() {}
 /** Contains-pattern for a JPQL "like ... escape '!'", or null when there is no search text. */
 public static String likePattern(String q) {
  if(q==null || q.isBlank()) return null;
  String text=q.strip();
  if(text.length()<2 || text.length()>100)
   throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Search text must be 2..100 characters.");
  // '!' is the LIKE escape character: user input can never act as a wildcard.
  return "%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
 }
}
