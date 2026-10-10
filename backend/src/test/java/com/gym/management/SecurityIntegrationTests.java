package com.gym.management;

import com.gym.management.entity.GymUser;
import com.gym.management.entity.Role;
import com.gym.management.repository.GymUserRepository;
import com.gym.management.repository.MemberRepository;
import com.gym.management.repository.MemberActivationRepository;
import com.gym.management.repository.AttendanceRepository;
import com.gym.management.entity.Attendance;
import com.gym.management.entity.MemberActivation;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.Objects;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:securitytest;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop","GYM_CORS_ALLOWED_ORIGINS=https://gymflow-frontend-pied.vercel.app"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class SecurityIntegrationTests {
 @Autowired MockMvc mvc; @Autowired GymUserRepository users; @Autowired MemberRepository members; @Autowired MemberActivationRepository activations; @Autowired AttendanceRepository attendance; @Autowired PasswordEncoder passwords; @Autowired FilterChainProxy security; @Autowired Environment environment;
 @BeforeEach void accounts(){if(!users.existsByUsernameIgnoreCase("security-admin"))users.save(new GymUser("security-admin",passwords.encode("admin-test-password-123"),Role.ADMIN));if(!users.existsByUsernameIgnoreCase("security-staff"))users.save(new GymUser("security-staff",passwords.encode("staff-test-password-123"),Role.STAFF));}
 private String loginBody(String username,String password){return "{\"username\":\""+username+"\",\"password\":\""+password+"\"}";}
 private String emailLoginBody(String identifier,String password){return "{\"usernameOrEmail\":\""+identifier+"\",\"password\":\""+password+"\"}";}
 private long jsonLong(String json,String key){var matcher=java.util.regex.Pattern.compile("\\\""+key+"\\\":(\\d+)").matcher(json);assertTrue(matcher.find(),"Missing numeric JSON field "+key);return Long.parseLong(matcher.group(1));}
 private long jsonPlanId(String json){var matcher=java.util.regex.Pattern.compile("\\\"plan\\\":\\{[^}]*\\\"id\\\":(\\d+)").matcher(json);assertTrue(matcher.find(),"Missing plan id");return Long.parseLong(matcher.group(1));}
 private String jsonString(String json,String key){var matcher=java.util.regex.Pattern.compile("\\\""+key+"\\\":\\\"([^\\\"]*)\\\"").matcher(json);assertTrue(matcher.find(),"Missing string JSON field "+key);return matcher.group(1);}
 private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) throws Exception {
  var result=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();Cookie cookie=result.getResponse().getCookie("XSRF-TOKEN");assertNotNull(cookie,"CSRF initialization must set its readable cookie");
  return request.cookie(cookie).header("X-XSRF-TOKEN",cookie.getValue());
 }
 private MockHttpSession signIn(String username,String password) throws Exception {
  var result=mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(loginBody(username,password))).andExpect(status().isOk()).andReturn();
  return (MockHttpSession)result.getRequest().getSession(false);
 }
 private long createMember(MockHttpSession admin,String name,String email) throws Exception {String body="{\"fullName\":\""+name+"\",\"email\":\""+email+"\",\"phone\":\"5550100\",\"category\":\"GIRLS\",\"joiningDate\":\""+LocalDate.now()+"\"}";String json=mvc.perform(csrf(post("/api/members")).session(admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();return jsonLong(json,"id");}
 private String issueMemberToken(MockHttpSession admin,long memberId,String username) throws Exception {String body="{\"memberId\":"+memberId+",\"username\":\""+username+"\"}";String json=mvc.perform(csrf(post("/api/admin/member-activations")).session(admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();return jsonString(json,"token");}
 private void activate(String token,String password) throws Exception {mvc.perform(csrf(post("/api/auth/activate-member")).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\""+token+"\",\"password\":\""+password+"\"}")).andExpect(status().isNoContent());}
 @Test void unauthenticatedApiIsUnauthorized() throws Exception {mvc.perform(get("/api/members")).andExpect(status().isUnauthorized());}
 @Test void corsUsesConfiguredExactOriginsAndCredentials() throws Exception {
  mvc.perform(options("/api/auth/me").header("Origin","https://gymflow-frontend-pied.vercel.app").header("Access-Control-Request-Method","GET"))
    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://gymflow-frontend-pied.vercel.app")).andExpect(header().string("Access-Control-Allow-Credentials","true"));
  mvc.perform(options("/api/auth/me").header("Origin","https://not-allowed.example.test").header("Access-Control-Request-Method","GET"))
    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
 }
 @Test void productionProfileMarksSessionAndCsrfCookiesSecure() throws Exception {
  var csrfResponse=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
  Cookie[] csrfCookies=csrfResponse.getCookies();assertTrue(csrfCookies.length>0,"CSRF bootstrap must issue a cookie");Cookie csrfCookie=csrfCookies[csrfCookies.length-1];
  assertTrue(csrfCookie.getSecure());assertEquals(jsonString(csrfResponse.getContentAsString(),"token"),csrfCookie.getValue());
  var result=mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(loginBody("security-staff","staff-test-password-123"))).andExpect(status().isOk()).andReturn();
  assertNotNull(result.getRequest().getSession(false));assertEquals("true",environment.getProperty("server.servlet.session.cookie.secure"));
 }
 @Test void validAndInvalidLogin() throws Exception {
  var response=mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(loginBody("security-admin","admin-test-password-123"))).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("security-admin")).andExpect(jsonPath("$.roles[0]").value("ADMIN")).andExpect(jsonPath("$.roles.length()").value(1)).andReturn();
  assertNotNull(response.getRequest().getSession(false));assertTrue(passwords.matches("admin-test-password-123",users.findByUsernameIgnoreCase("security-admin").orElseThrow().getPasswordHash()));
  assertFalse(new com.gym.management.dto.LoginRequest("security-admin","admin-test-password-123").toString().contains("admin-test-password-123"));assertFalse(new com.gym.management.dto.AccountRequest("staff","staff-test-password-123",Role.STAFF).toString().contains("staff-test-password-123"));
  mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(loginBody("security-admin","wrong-password"))).andExpect(status().isUnauthorized());
 }
 @Test void corsAllowsOnlyConfiguredCredentialedSpaOrigins() throws Exception {
  mvc.perform(get("/api/auth/csrf").header("Origin","https://gymflow-frontend-pied.vercel.app")).andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://gymflow-frontend-pied.vercel.app")).andExpect(header().string("Access-Control-Allow-Credentials","true"));
  mvc.perform(get("/api/auth/csrf").header("Origin","http://localhost:5173")).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  mvc.perform(get("/api/auth/csrf").header("Origin","https://untrusted.example")).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
 }
 @Test void accountCreationRejectsPasswordsPastBcryptInputLimit() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String tooLong="x".repeat(73);String body="{\"username\":\"long-password-user\",\"password\":\""+tooLong+"\",\"role\":\"STAFF\"}";
  mvc.perform(csrf(post("/api/admin/users")).session(admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
 }
 @Test void browserCsrfCookieCanBeSentAsTheSpaHeader() throws Exception {
  var filters=security.getFilters("/api/auth/csrf");var csrfFilter=(CsrfFilter)filters.stream().filter(CsrfFilter.class::isInstance).findFirst().orElseThrow();
  assertInstanceOf(CookieCsrfTokenRepository.class,ReflectionTestUtils.getField(csrfFilter,"tokenRepository"));
  assertNotNull(signIn("security-staff","staff-test-password-123"));
 }
 @Test void staffCanReadEverydayDataButCannotPerformAdminMutations() throws Exception {
  MockHttpSession session=signIn("security-staff","staff-test-password-123");
  mvc.perform(get("/api/members").session(session)).andExpect(status().isOk());mvc.perform(get("/api/plans").session(session)).andExpect(status().isOk());
  mvc.perform(csrf(post("/api/plans")).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Blocked Plan\",\"durationDays\":30,\"price\":50}")).andExpect(status().isForbidden());
  mvc.perform(csrf(post("/api/payments/1/process")).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}")).andExpect(status().isForbidden());
 }
 @Test void adminCanUseExistingApiAndStaffCannotManageAccounts() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");
  mvc.perform(get("/api/reports/dashboard").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.totalMembers").exists());
  mvc.perform(csrf(post("/api/admin/users")).session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"new-staff\",\"password\":\"a-long-password-123\",\"role\":\"STAFF\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("STAFF"));
  mvc.perform(get("/api/admin/users").session(signIn("security-staff","staff-test-password-123"))).andExpect(status().isForbidden());
 }
 @Test void onlyAdminCanListActivationCandidatesAndIssueMemberActivation() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");
  String email="activation-ui-"+UUID.randomUUID()+"@example.test";long memberId=createMember(admin,"Activation UI Member",email);
  mvc.perform(get("/api/admin/member-activations/available-members").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == "+memberId+")]").exists());
  mvc.perform(get("/api/admin/member-activations/available-members").session(signIn("security-staff","staff-test-password-123"))).andExpect(status().isForbidden());
  String body="{\"memberId\":"+memberId+",\"username\":\"activation-ui-"+UUID.randomUUID()+"\"}";
  mvc.perform(csrf(post("/api/admin/member-activations")).session(admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty()).andExpect(jsonPath("$.email").value(email));
  mvc.perform(csrf(post("/api/admin/member-activations")).session(signIn("security-staff","staff-test-password-123")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
 }
 @Test void fitnessMeasurementsArePrivateAndRejectFutureDates() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String ownerEmail="fit-owner-"+UUID.randomUUID()+"@example.test",otherEmail="fit-other-"+UUID.randomUUID()+"@example.test";
  long ownerId=createMember(admin,"Fitness Owner",ownerEmail),otherId=createMember(admin,"Fitness Other",otherEmail);MockHttpSession owner=signInMember(admin,ownerId,"fitness-owner-"+UUID.randomUUID(),ownerEmail),other=signInMember(admin,otherId,"fitness-other-"+UUID.randomUUID(),otherEmail);
  String body="{\"measurementDate\":\""+LocalDate.now()+"\",\"weightKg\":72.5,\"waistCm\":81.0}";
  String record=mvc.perform(csrf(post("/api/member/measurements")).session(owner).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.weightKg").value(72.5)).andReturn().getResponse().getContentAsString();long id=jsonLong(record,"id");
  mvc.perform(get("/api/member/measurements").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(1)));
  mvc.perform(get("/api/member/measurements").session(other)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(0)));
  mvc.perform(csrf(put("/api/member/measurements/"+id)).session(other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
  mvc.perform(csrf(post("/api/member/measurements")).session(owner).contentType(MediaType.APPLICATION_JSON).content(body.replace(LocalDate.now().toString(),LocalDate.now().plusDays(1).toString()))).andExpect(status().isBadRequest());
 }
 @Test void goalProgressAndNutritionTotalsUseOnlyTheMembersPersistedRecords() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String email="wellness-"+UUID.randomUUID()+"@example.test",otherEmail="wellness-other-"+UUID.randomUUID()+"@example.test";long ownerId=createMember(admin,"Wellness Owner",email),otherId=createMember(admin,"Wellness Other",otherEmail);MockHttpSession owner=signInMember(admin,ownerId,"wellness-"+UUID.randomUUID(),email),other=signInMember(admin,otherId,"wellness-other-"+UUID.randomUUID(),otherEmail);
  String day=LocalDate.now().toString();mvc.perform(csrf(post("/api/member/measurements")).session(owner).contentType(MediaType.APPLICATION_JSON).content("{\"measurementDate\":\""+day+"\",\"weightKg\":80}")).andExpect(status().isCreated());
  String goal="{\"title\":\"Steady progress\",\"description\":\"\",\"goalType\":\"WEIGHT_LOSS\",\"targetValue\":75,\"unit\":\"WEIGHT_KG\",\"targetDate\":\""+LocalDate.now().plusDays(30)+"\"}";long goalId=jsonLong(mvc.perform(csrf(post("/api/member/goals")).session(owner).contentType(MediaType.APPLICATION_JSON).content(goal)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"id");
  mvc.perform(csrf(post("/api/member/measurements")).session(owner).contentType(MediaType.APPLICATION_JSON).content("{\"measurementDate\":\""+day+"\",\"weightKg\":77}")).andExpect(status().isCreated());mvc.perform(get("/api/member/goals").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$[0].progressPercent").value(60));
  mvc.perform(csrf(post("/api/member/goals/"+goalId+"/complete")).session(owner)).andExpect(status().isConflict());mvc.perform(csrf(post("/api/member/measurements")).session(owner).contentType(MediaType.APPLICATION_JSON).content("{\"measurementDate\":\""+day+"\",\"weightKg\":75}")).andExpect(status().isCreated());mvc.perform(csrf(post("/api/member/goals/"+goalId+"/complete")).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
  String now=LocalDateTime.now().withNano(0).toString();mvc.perform(csrf(post("/api/member/nutrition/meals")).session(owner).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Lunch\",\"category\":\"LUNCH\",\"occurredAt\":\""+now+"\",\"calories\":450,\"proteinGrams\":20,\"carbohydrateGrams\":50,\"fatGrams\":12}" )).andExpect(status().isCreated());
  String water="{\"amountMl\":500,\"occurredAt\":\""+now+"\",\"idempotencyKey\":\"water-"+UUID.randomUUID()+"\"}";mvc.perform(csrf(post("/api/member/nutrition/water")).session(owner).contentType(MediaType.APPLICATION_JSON).content(water)).andExpect(status().isCreated());
  mvc.perform(get("/api/member/nutrition?date="+day).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.calories").value(450)).andExpect(jsonPath("$.proteinGrams").value(20)).andExpect(jsonPath("$.waterTotalMl").value(500));
  mvc.perform(get("/api/member/nutrition?date="+day).session(other)).andExpect(status().isOk()).andExpect(jsonPath("$.calories").value(0)).andExpect(jsonPath("$.waterTotalMl").value(0));
 }
 @Test void workoutsArePersistedAndDuplicateSessionSubmissionIsRejected() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String email="workout-"+UUID.randomUUID()+"@example.test";long memberId=createMember(admin,"Workout Member",email);MockHttpSession member=signInMember(admin,memberId,"workout-"+UUID.randomUUID(),email);
  String exercises=mvc.perform(get("/api/member/exercises").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").exists()).andReturn().getResponse().getContentAsString();long exerciseId=jsonLong(exercises,"id");
  String plan="{\"name\":\"Test routine\",\"description\":\"\",\"exercises\":[{\"exerciseId\":"+exerciseId+",\"targetSets\":3,\"targetReps\":10,\"restSeconds\":60,\"position\":0}]}";
  String planJson=mvc.perform(csrf(post("/api/member/workouts/plans")).session(member).contentType(MediaType.APPLICATION_JSON).content(plan)).andExpect(status().isCreated()).andExpect(jsonPath("$.exercises.length()").value(1)).andReturn().getResponse().getContentAsString();long planId=jsonLong(planJson,"id");String key="workout-"+UUID.randomUUID();String session="{\"idempotencyKey\":\""+key+"\",\"planId\":"+planId+",\"durationMinutes\":30,\"exercises\":[{\"exerciseId\":"+exerciseId+",\"completedSets\":3,\"repsPerSet\":10,\"weightKg\":null}]}";
  mvc.perform(csrf(post("/api/member/workouts/sessions")).session(member).contentType(MediaType.APPLICATION_JSON).content(session)).andExpect(status().isCreated()).andExpect(jsonPath("$.completedAt").exists());
  mvc.perform(csrf(post("/api/member/workouts/sessions")).session(member).contentType(MediaType.APPLICATION_JSON).content(session)).andExpect(status().isConflict());
  String startBody="{\"idempotencyKey\":\"start-"+UUID.randomUUID()+"\",\"planId\":"+planId+",\"workoutName\":\"Test routine\"}";String active=mvc.perform(csrf(post("/api/member/workouts/sessions/start")).session(member).contentType(MediaType.APPLICATION_JSON).content(startBody)).andExpect(status().isCreated()).andExpect(jsonPath("$.completedAt").isEmpty()).andReturn().getResponse().getContentAsString();long activeId=jsonLong(active,"id");
  mvc.perform(get("/api/member/fitness/summary").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$.completedWorkouts").value(1));
  String finish="{\"durationMinutes\":30,\"exercises\":[{\"exerciseId\":"+exerciseId+",\"completedSets\":2,\"repsPerSet\":8}]}";mvc.perform(csrf(post("/api/member/workouts/sessions/"+activeId+"/complete")).session(member).contentType(MediaType.APPLICATION_JSON).content(finish)).andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").exists());
  mvc.perform(get("/api/member/workouts/sessions").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(2)));
  mvc.perform(get("/api/member/workouts/plans").session(signIn("security-staff","staff-test-password-123"))).andExpect(status().isForbidden());
 }
 @Test void announcementsAreAdminManagedAndMemberNotificationsAreOwnerScoped() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String email="announce-"+UUID.randomUUID()+"@example.test",otherEmail="announce-other-"+UUID.randomUUID()+"@example.test";long memberId=createMember(admin,"Announcement Member",email),otherId=createMember(admin,"Announcement Other",otherEmail);MockHttpSession member=signInMember(admin,memberId,"announce-"+UUID.randomUUID(),email),other=signInMember(admin,otherId,"announce-other-"+UUID.randomUUID(),otherEmail);
  String body="{\"title\":\"Gym update\",\"message\":\"Opening hours this weekend\",\"audience\":\"MEMBERS\",\"startsAt\":\""+LocalDate.now().atStartOfDay()+"\",\"endsAt\":null,\"active\":true}";
  mvc.perform(csrf(post("/api/admin/announcements")).session(admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
  mvc.perform(csrf(post("/api/admin/announcements")).session(signIn("security-staff","staff-test-password-123")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  String list=mvc.perform(get("/api/member/notifications").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$[0].category").value("ANNOUNCEMENT")).andExpect(jsonPath("$[0].read").value(false)).andReturn().getResponse().getContentAsString();long notificationId=jsonLong(list,"id");
  mvc.perform(get("/api/member/notifications").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(1)));
  mvc.perform(csrf(post("/api/member/notifications/"+notificationId+"/read")).session(signIn("security-staff","staff-test-password-123"))).andExpect(status().isForbidden());
  mvc.perform(csrf(post("/api/member/notifications/"+notificationId+"/read")).session(other)).andExpect(status().isNotFound());
  mvc.perform(csrf(post("/api/member/notifications/"+notificationId+"/read")).session(member)).andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
 }
 @Test void logoutInvalidatesTheAuthenticatedSession() throws Exception {
  MockHttpSession session=signIn("security-admin","admin-test-password-123");mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("security-admin"));
  mvc.perform(csrf(post("/api/auth/logout")).session(session)).andExpect(status().isNoContent());mvc.perform(get("/api/members").session(session)).andExpect(status().isUnauthorized());
 }
 @Test void csrfIsRequiredForUnsafeApiCalls() throws Exception {
  MockHttpSession session=signIn("security-staff","staff-test-password-123");mvc.perform(post("/api/members").session(session).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
 }
 @Test void loginRejectsMissingAndInvalidCsrfTokens() throws Exception {
  String body=loginBody("security-staff","staff-test-password-123");
  mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
  var bootstrap=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
  Cookie cookie=bootstrap.getCookie("XSRF-TOKEN");assertNotNull(cookie);
  mvc.perform(post("/api/auth/login").cookie(cookie).header("X-XSRF-TOKEN","invalid-token")
    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
 }
 @Test void memberCrudCannotGrantOrExtendMembershipOutsideSuccessfulPayment() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");
  String planJson=mvc.perform(csrf(post("/api/plans")).session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Paid plan\",\"durationDays\":30,\"price\":25.00,\"active\":true}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  long planId=jsonLong(planJson,"id");String today=LocalDate.now().toString(),farFuture=LocalDate.now().plusYears(10).toString();String email="membership-"+UUID.randomUUID()+"@example.test";
  String memberJson="{\"fullName\":\"Membership rule test\",\"email\":\""+email+"\",\"phone\":\"5550100\",\"category\":\"GIRLS\",\"joiningDate\":\""+today+"\",\"planId\":"+planId+",\"membershipStart\":\""+today+"\",\"membershipEnd\":\""+farFuture+"\"}";
  String createdJson=mvc.perform(csrf(post("/api/members")).session(admin).contentType(MediaType.APPLICATION_JSON).content(memberJson)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();long memberId=jsonLong(createdJson,"id");
  assertTrue(createdJson.contains("\"membershipStatus\":\"NO MEMBERSHIP\""));
  String requestJson="{\"memberId\":"+memberId+",\"planId\":"+planId+",\"method\":\"CASH\",\"purpose\":\"MEMBERSHIP_PURCHASE\",\"idempotencyKey\":\"membership-rule-"+UUID.randomUUID()+"\"}";
  String paymentJson=mvc.perform(csrf(post("/api/payments")).session(admin).contentType(MediaType.APPLICATION_JSON).content(requestJson)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();long paymentId=jsonLong(paymentJson,"id");
  mvc.perform(csrf(post("/api/payments/"+paymentId+"/process")).session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}" )).andExpect(status().isOk());
  String beforeJson=mvc.perform(get("/api/members/"+memberId).session(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();String paidEnd=jsonString(beforeJson,"membershipEnd");
  MockHttpSession staff=signIn("security-staff","staff-test-password-123");String tampered="{\"fullName\":\"Membership rule test\",\"email\":\""+email+"\",\"phone\":\"5550101\",\"category\":\"GIRLS\",\"joiningDate\":\""+today+"\",\"planId\":null,\"membershipStart\":\""+today+"\",\"membershipEnd\":\""+farFuture+"\"}";
  String afterJson=mvc.perform(csrf(put("/api/members/"+memberId)).session(staff).contentType(MediaType.APPLICATION_JSON).content(tampered)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertEquals(paidEnd,jsonString(afterJson,"membershipEnd"));assertEquals(planId,jsonPlanId(afterJson));
  mvc.perform(csrf(delete("/api/members/"+memberId)).session(staff)).andExpect(status().isForbidden());mvc.perform(csrf(delete("/api/members/"+memberId)).session(admin)).andExpect(status().isConflict());
 }
 @Test void memberCanActivateAndLoginByRegisteredEmailAndOnlyReadOwnDashboard() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String email="portal-"+UUID.randomUUID()+"@example.test";long memberId=createMember(admin,"Portal Member",email);String token=issueMemberToken(admin,memberId,"portal-user");
  MemberActivation activation=activations.findByMemberId(memberId).orElseThrow();assertNotEquals(token,activation.getTokenHash());assertEquals(memberId,activation.getMember().getId());
  activate(token,"member-password-123");MockHttpSession member=signInEmail(email,"member-password-123");
  mvc.perform(get("/api/member/dashboard").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$.profile.fullName").value("Portal Member")).andExpect(jsonPath("$.profile.email").value(email)).andExpect(jsonPath("$.membership.status").value("NOT SUBSCRIBED")).andExpect(jsonPath("$.attendanceThisMonth").value(0)).andExpect(jsonPath("$.successfulPaymentCount").value(0)).andExpect(jsonPath("$.recentPayments.length()").value(0));
  mvc.perform(get("/api/member/attendance").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(0)));
  mvc.perform(get("/api/member/payments").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(0)));
  mvc.perform(get("/api/member/dashboard").session(signIn("portal-user","member-password-123"))).andExpect(status().isOk()).andExpect(jsonPath("$.profile.username").value("portal-user"));
  mvc.perform(get("/api/members").session(member)).andExpect(status().isForbidden());mvc.perform(get("/api/member/dashboard").session(signIn("security-staff","staff-test-password-123"))).andExpect(status().isForbidden());
  mvc.perform(csrf(put("/api/member/profile")).session(member)).andExpect(status().isMethodNotAllowed());
 }
 private MockHttpSession signInEmail(String email,String password) throws Exception {var result=mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(emailLoginBody(email,password))).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("MEMBER")).andReturn();return (MockHttpSession)result.getRequest().getSession(false);}
 @Test void memberActivationTokenIsExpiringAndSingleUse() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");long expiredMember=createMember(admin,"Expired Link Member","expired-"+UUID.randomUUID()+"@example.test");String expired=issueMemberToken(admin,expiredMember,"expired-user");MemberActivation record=activations.findByMemberId(expiredMember).orElseThrow();ReflectionTestUtils.setField(record,"expiresAt",java.time.Instant.now().minusSeconds(1));activations.save(record);
  mvc.perform(csrf(post("/api/auth/activate-member")).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\""+expired+"\",\"password\":\"member-password-123\"}")).andExpect(status().isBadRequest());
  long memberId=createMember(admin,"Single Use Member","single-"+UUID.randomUUID()+"@example.test");String token=issueMemberToken(admin,memberId,"single-use-user");activate(token,"member-password-123");
  mvc.perform(csrf(post("/api/auth/activate-member")).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\""+token+"\",\"password\":\"member-password-123\"}")).andExpect(status().isBadRequest());
  mvc.perform(csrf(post("/api/admin/member-activations")).session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":"+memberId+",\"username\":\"single-use-user\"}")).andExpect(status().isConflict());
 }
 @Test void inactiveMemberAccountCannotLoginByUsernameOrEmail() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String email="inactive-"+UUID.randomUUID()+"@example.test";long memberId=createMember(admin,"Inactive Member",email);activate(issueMemberToken(admin,memberId,"inactive-user"),"member-password-123");
  GymUser account=users.findByUsernameIgnoreCase("inactive-user").orElseThrow();mvc.perform(csrf(put("/api/admin/users/"+account.getId()+"/enabled")).session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
  mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(loginBody("inactive-user","member-password-123"))).andExpect(status().isUnauthorized());
  mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(emailLoginBody(email,"member-password-123"))).andExpect(status().isUnauthorized());
 }
 @Test void memberReceiptIsRestrictedToTheOwningMember() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String ownerEmail="owner-"+UUID.randomUUID()+"@example.test",otherEmail="other-"+UUID.randomUUID()+"@example.test";
  long ownerId=createMember(admin,"Receipt Owner",ownerEmail),otherId=createMember(admin,"Other Member",otherEmail);MockHttpSession owner=signInMember(admin,ownerId,"receipt-owner",ownerEmail);MockHttpSession other=signInMember(admin,otherId,"receipt-other",otherEmail);
  String plan=mvc.perform(csrf(post("/api/plans")).session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Receipt plan "+UUID.randomUUID()+"\",\"durationDays\":30,\"price\":1500.00}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();long planId=jsonLong(plan,"id");
  String request="{\"memberId\":"+ownerId+",\"planId\":"+planId+",\"method\":\"CASH\",\"purpose\":\"MEMBERSHIP_PURCHASE\",\"idempotencyKey\":\"receipt-"+UUID.randomUUID()+"\"}";String payment=mvc.perform(csrf(post("/api/payments")).session(admin).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();long paymentId=jsonLong(payment,"id");
  mvc.perform(csrf(post("/api/payments/"+paymentId+"/process")).session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}")).andExpect(status().isOk());
  mvc.perform(get("/api/member/payments").session(other)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(0)));
  mvc.perform(get("/api/member/payments/"+paymentId+"/receipt").session(other)).andExpect(status().isNotFound());mvc.perform(get("/api/member/payments/"+paymentId+"/receipt").session(owner)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("₹1,500.00")));
 }
 @Test void attendanceActivityIsGroupedScopedAndRejectsInvalidRanges() throws Exception {
  MockHttpSession admin=signIn("security-admin","admin-test-password-123");String ownerEmail="activity-owner-"+UUID.randomUUID()+"@example.test",otherEmail="activity-other-"+UUID.randomUUID()+"@example.test";
  long ownerId=createMember(admin,"Activity Owner",ownerEmail),otherId=createMember(admin,"Activity Other",otherEmail);MockHttpSession owner=signInMember(admin,ownerId,"activity-owner-"+UUID.randomUUID(),ownerEmail),other=signInMember(admin,otherId,"activity-other-"+UUID.randomUUID(),otherEmail);
  LocalDate today=LocalDate.now(),yesterday=today.minusDays(1);var member=members.findById(ownerId).orElseThrow();
  String range="?from="+yesterday+"&to="+today;
  mvc.perform(get("/api/member/attendance/activity"+range).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.visitsInPeriod").value(0)).andExpect(jsonPath("$.days",org.hamcrest.Matchers.hasSize(0))).andExpect(jsonPath("$.currentStreak").value(0));
  Attendance first=attendance.save(new Attendance(member,today.atTime(7,30)));first.setCheckOutAt(today.atTime(8,30));attendance.save(first);attendance.save(new Attendance(member,today.atTime(18,0)));attendance.save(new Attendance(member,yesterday.atTime(9,0)));
  attendance.save(new Attendance(members.findById(otherId).orElseThrow(),today.atTime(10,0)));
  mvc.perform(get("/api/member/attendance/activity"+range).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.visitsInPeriod").value(3)).andExpect(jsonPath("$.visitsThisMonth").value(today.getDayOfMonth()==1?2:3)).andExpect(jsonPath("$.currentStreak").value(2)).andExpect(jsonPath("$.longestStreak").value(2)).andExpect(jsonPath("$.days",org.hamcrest.Matchers.hasSize(2))).andExpect(jsonPath("$.days[1].visitCount").value(2)).andExpect(jsonPath("$.days[1].visits[0].checkOutAt").isNotEmpty());
  mvc.perform(get("/api/member/attendance/activity"+range).session(other)).andExpect(status().isOk()).andExpect(jsonPath("$.visitsInPeriod").value(1)).andExpect(jsonPath("$.days",org.hamcrest.Matchers.hasSize(1)));
  MockHttpSession staff=signIn("security-staff","staff-test-password-123");
  mvc.perform(get("/api/member/attendance/activity"+range).session(staff)).andExpect(status().isForbidden());mvc.perform(get("/api/member/attendance/activity"+range).session(admin)).andExpect(status().isForbidden());
  mvc.perform(get("/api/attendance").session(staff)).andExpect(status().isOk());mvc.perform(get("/api/attendance").session(admin)).andExpect(status().isOk());
  mvc.perform(get("/api/member/attendance/activity?from="+today.minusDays(366)+"&to="+today).session(owner)).andExpect(status().isBadRequest());
  mvc.perform(get("/api/member/attendance/activity?from="+today+"&to="+today.plusDays(1)).session(owner)).andExpect(status().isBadRequest());
  mvc.perform(get("/api/member/attendance/activity"+range)).andExpect(status().isUnauthorized());
 }
 private MockHttpSession signInMember(MockHttpSession admin,long memberId,String username,String email) throws Exception {activate(issueMemberToken(admin,memberId,username),"member-password-123");return signInEmail(email,"member-password-123");}
}
