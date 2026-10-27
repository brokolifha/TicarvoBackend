package com.ticarvo.shared.validation;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
public final class PageValidation {
 private PageValidation() {}
 public static void validatePage(int page,int size) {
  if(page<0 || page>100000 || size<1 || size>100)
   throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Page must be 0..100000 and size 1..100.");
 }
}
