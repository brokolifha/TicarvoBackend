param([string]$BaseUrl='http://127.0.0.1:8080',[Parameter(Mandatory)][string]$MailOutbox)
$ErrorActionPreference='Stop'
$results=[System.Collections.Generic.List[object]]::new()
function New-Client {
  $handler=[System.Net.Http.HttpClientHandler]::new()
  $handler.UseCookies=$true
  $handler.AllowAutoRedirect=$false
  $client=[System.Net.Http.HttpClient]::new($handler)
  $client.BaseAddress=[uri]$BaseUrl
  return @{ Client=$client; Handler=$handler }
}
function Send-Request($Session,[string]$Method,[string]$Path,$Data=$null,[string]$Csrf='', [hashtable]$Headers=@{},[switch]$Form,[string]$MediaType='application/json',[switch]$Chunked) {
  $request=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method),$Path)
  if($Chunked){$request.Headers.TransferEncodingChunked=$true}
  if($null -ne $Data) {
    if($Form) {
      $pairs=[System.Collections.Generic.Dictionary[string,string]]::new()
      foreach($key in $Data.Keys){$pairs.Add($key,[string]$Data[$key])}
      $request.Content=[System.Net.Http.FormUrlEncodedContent]::new($pairs)
    } else {
      $request.Content=[System.Net.Http.StringContent]::new(($Data | ConvertTo-Json -Compress),[System.Text.Encoding]::UTF8,$MediaType)
    }
  }
  if($Csrf){[void]$request.Headers.TryAddWithoutValidation('X-CSRF-TOKEN',$Csrf)}
  foreach($key in $Headers.Keys){[void]$request.Headers.TryAddWithoutValidation($key,[string]$Headers[$key])}
  $response=$Session.Client.SendAsync($request).GetAwaiter().GetResult()
  $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  $json=$null
  if($body){try{$json=$body | ConvertFrom-Json}catch{}}
  return @{Status=[int]$response.StatusCode; Json=$json; Body=$body; Headers=$response.Headers}
}
function Check([string]$Name,[bool]$Passed,$Observed) {
  $results.Add([pscustomobject]@{name=$Name;passed=$Passed;observed=$Observed})
}
function Csrf($Session){(Send-Request $Session GET '/api/v1/auth/csrf').Json.token}
function MailToken([string]$Email){ $file=Get-ChildItem $MailOutbox -Filter *.txt | Where-Object { (Get-Content $_.FullName -TotalCount 1) -eq "To: $Email" } | Sort-Object Name | Select-Object -Last 1; ((Get-Content $file.FullName) | Select-Object -Last 1).Trim() }
function SessionCookie($Session){$Session.Handler.CookieContainer.GetCookies([uri]$BaseUrl)['TICARVO_SESSION'].Value}
function Save-Results {
  [pscustomobject]@{timestamp=[datetime]::UtcNow.ToString('o');baseUrl=$BaseUrl;checks=$results} | ConvertTo-Json -Depth 7 | Set-Content -Encoding utf8 (Join-Path $PSScriptRoot 'http-results.json')
  $results | Select-Object name,passed,observed | Format-Table -AutoSize
}
try {
  $a=New-Client; $b=New-Client; $anonymous=New-Client
  $unique=[guid]::NewGuid().ToString('N')
  $emailA="security-m1-a-$unique@example.invalid"; $emailB="security-m1-b-$unique@example.invalid"
  $password='Synthetic-M1-Password!95'
  $r=Send-Request $anonymous GET '/api/v1/companies'
  Check 'anonymous companies denied' ($r.Status -eq 401) $r.Status
  $r=Send-Request $anonymous GET '/api/v1/auth/me'
  Check 'anonymous me denied' ($r.Status -eq 401) $r.Status
  $r=Send-Request $anonymous GET '/actuator/env'
  Check 'actuator env not public' ($r.Status -in @(401,403,404)) $r.Status
  $tokenA=Csrf $a; $tokenB=Csrf $b
  $r=Send-Request $a POST '/api/v1/auth/register' @{email=$emailA;password=$password;displayName='Security A'}
  Check 'register requires CSRF' ($r.Status -eq 403) $r.Status
  $r=Send-Request $a POST '/api/v1/auth/register' @{email=$emailA;password=$password;displayName='Security A'} $tokenA
  # A-04: sign-up always answers 202 with no body; the user id comes from /auth/me after login.
  Check 'register valid A' ($r.Status -eq 202) $r.Status
  Check 'registration excludes secrets' ($r.Body -notmatch 'password|hash|session') $r.Status
  $r=Send-Request $b POST '/api/v1/auth/register' @{email=$emailB;password=$password;displayName='Security B'} $tokenB
  Check 'register valid B' ($r.Status -eq 202) $r.Status
  $r=Send-Request $a GET '/api/v1/auth/me'
  Check 'registration does not auto-login' ($r.Status -eq 401) $r.Status
  $r=Send-Request $a POST '/api/v1/auth/register' @{email=$emailA.ToUpperInvariant();password=$password;displayName='Security A'} $tokenA
  Check 'normalized duplicate answers like a new sign-up (A-04)' ($r.Status -eq 202 -and -not $r.Body) $r.Status
  $r=Send-Request $a POST '/api/v1/auth/login' @{email=$emailA;password=$password} $tokenA -Form
  Check 'unverified login rejected' ($r.Status -eq 403 -and $r.Json.code -eq 'EMAIL_NOT_VERIFIED') $r.Status
  foreach($pair in @(@('A',$emailA),@('B',$emailB))){
    $r=Send-Request $anonymous POST '/api/v1/auth/email-verification' @{token=(MailToken $pair[1])} (Csrf $anonymous)
    Check "email verified $($pair[0])" ($r.Status -eq 204) $r.Status
  }
  $oldCookie=SessionCookie $a
  $r=Send-Request $a POST '/api/v1/auth/login' @{email=$emailA;password=$password} -Form
  Check 'login requires CSRF' ($r.Status -eq 403) $r.Status
  $r=Send-Request $a POST '/api/v1/auth/login' @{email=$emailA;password=$password} $tokenA -Form
  Check 'login valid A' ($r.Status -eq 204) $r.Status
  $userA=(Send-Request $a GET '/api/v1/auth/me').Json.id
  $newCookie=SessionCookie $a
  Check 'login rotates session cookie' ($oldCookie -and $newCookie -and $oldCookie -ne $newCookie) 'cookie values redacted'
  $r=Send-Request $a POST '/api/v1/companies' @{name='Old CSRF test'} $tokenA
  Check 'login invalidates old CSRF' ($r.Status -eq 403) $r.Status
  $old=New-Client
  $old.Handler.CookieContainer.Add([uri]$BaseUrl,[System.Net.Cookie]::new('TICARVO_SESSION',$oldCookie,'/'))
  $r=Send-Request $old GET '/api/v1/auth/me'
  Check 'pre-login cookie cannot access identity' ($r.Status -eq 401) $r.Status
  $tokenA=Csrf $a
  $r=Send-Request $b POST '/api/v1/auth/login' @{email=$emailB;password=$password} $tokenB -Form
  Check 'login valid B' ($r.Status -eq 204) $r.Status
  $userB=(Send-Request $b GET '/api/v1/auth/me').Json.id
  $tokenB=Csrf $b
  $r=Send-Request $a POST '/api/v1/companies' @{name='Security A company'} $tokenA
  Check 'create owned company A' ($r.Status -eq 201 -and $r.Json.role -eq 'OWNER') $r.Status
  $companyA=$r.Json.id
  $r=Send-Request $b POST '/api/v1/companies' @{name='Security B company'} $tokenB
  Check 'create owned company B' ($r.Status -eq 201) $r.Status
  $companyB=$r.Json.id
  $r=Send-Request $a GET '/api/v1/companies'
  Check 'list only own firm and count' ($r.Status -eq 200 -and $r.Json.totalElements -eq 1 -and $r.Json.content[0].id -eq $companyA) $r.Status
  Check 'private response no-store' ($r.Headers.CacheControl.ToString() -match 'no-store') $r.Headers.CacheControl.ToString()
  $r=Send-Request $a GET "/api/v1/companies/$companyB"
  Check 'A cannot read B company' ($r.Status -eq 404) $r.Status
  $foreignBody=$r.Body
  $r=Send-Request $a GET "/api/v1/companies/$([guid]::NewGuid())"
  Check 'foreign and missing share error code' ($r.Status -eq 404 -and $r.Json.code -eq ($foreignBody|ConvertFrom-Json).code) $r.Status
  $r=Send-Request $a POST '/api/v1/companies' @{name='Override';ownerId=$userB;role='ADMIN'} $tokenA
  Check 'owner role injection rejected' ($r.Status -eq 400) $r.Status
  $r=Send-Request $a GET '/api/v1/companies?size=101'
  Check 'page size bound' ($r.Status -eq 400) $r.Status
  $r=Send-Request $a POST '/api/v1/companies' @{name='Wrong csrf'} 'definitely-invalid'
  Check 'wrong CSRF denied' ($r.Status -eq 403) $r.Status
  $oversized=@{name=('x' * 18000)}
  $r=Send-Request $a POST '/api/v1/companies' $oversized $tokenA -MediaType 'application/vnd.ticarvo+json' -Chunked
  Check 'chunked vendor JSON size limit' ($r.Status -eq 413) $r.Status
  $r=Send-Request $a OPTIONS '/api/v1/companies' $null '' @{Origin='https://evil.invalid';'Access-Control-Request-Method'='POST'}
  Check 'untrusted CORS origin not authorized' (!$r.Headers.Contains('Access-Control-Allow-Origin')) $r.Status
  $r=Send-Request $a GET '/api/v1/auth/me'
  Check 'authenticated identity excludes secrets' ($r.Status -eq 200 -and $r.Body -notmatch 'password|hash|session') $r.Status
  $authenticatedCookie=SessionCookie $a
  $r=Send-Request $a POST '/api/v1/auth/logout'
  Check 'logout requires CSRF' ($r.Status -eq 403) $r.Status
  $r=Send-Request $a POST '/api/v1/auth/logout' $null $tokenA
  Check 'logout valid' ($r.Status -eq 204) $r.Status
  $replay=New-Client
  $replay.Handler.CookieContainer.Add([uri]$BaseUrl,[System.Net.Cookie]::new('TICARVO_SESSION',$authenticatedCookie,'/'))
  $r=Send-Request $replay GET '/api/v1/auth/me'
  Check 'logged-out cookie cannot be replayed' ($r.Status -eq 401) $r.Status
  [pscustomobject]@{userA=$userA;userB=$userB;companyA=$companyA;companyB=$companyB} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $PSScriptRoot 'synthetic-ids.json')
} finally {
  Save-Results
  if (@($results | Where-Object { -not $_.passed }).Count -gt 0) {
    throw 'HTTP smoke checks failed; inspect scripts/http-results.json.'
  }
}

