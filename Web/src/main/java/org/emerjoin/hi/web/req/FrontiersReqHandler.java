package org.emerjoin.hi.web.req;

import com.google.gson.*;
import org.emerjoin.hi.web.ActiveUser;
import org.emerjoin.hi.web.AppContext;
import org.emerjoin.hi.web.FrontEnd;
import org.emerjoin.hi.web.RequestContext;
import org.emerjoin.hi.web.config.ConfigProvider;
import org.emerjoin.hi.web.config.Frontiers;
import org.emerjoin.hi.web.events.CSRFAttackAttemptEvent;
import org.emerjoin.hi.web.events.FrontierRequestEvent;
import org.emerjoin.hi.web.frontier.FileUpload;
import org.emerjoin.hi.web.frontier.FrontierInvoker;
import org.emerjoin.hi.web.frontier.exceptions.FrontierCallException;
import org.emerjoin.hi.web.frontier.exceptions.InvalidFrontierParamException;
import org.emerjoin.hi.web.frontier.exceptions.ResultConversionException;
import org.emerjoin.hi.web.frontier.exceptions.MissingFrontierParamException;
import org.emerjoin.hi.web.frontier.model.FrontierClass;
import org.emerjoin.hi.web.frontier.model.FrontierMethod;
import org.emerjoin.hi.web.frontier.model.MethodParam;
import org.emerjoin.hi.web.mvc.HTMLizer;
import org.emerjoin.hi.web.security.SecureTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

@HandleRequests(regexp = "jbind:[$_A-Za-z0-9=]+", supportPostMethod = true)
@ApplicationScoped
public class FrontiersReqHandler extends ReqHandler {

    private static Map<String,FrontierClass> frontiersMap = new HashMap();
    private static final Logger LOGGER = LoggerFactory.getLogger(FrontiersReqHandler.class);

    public static void addFrontier(FrontierClass frontierClass){
        frontiersMap.put(frontierClass.getSimpleName(),frontierClass);
    }

    public static boolean frontierExists(String name){
        return frontiersMap.containsKey(name);
    }

    public static FrontierClass getFrontier(String name){
        return frontiersMap.get(name);
    }

    @Inject
    private AppContext appContext;
    @Inject
    private FrontEnd frontEnd;
    @Inject
    private ServletContext servletContext;
    @Inject
    private RequestContext requestContext;

    @Inject
    private ActiveUser activeUser;

    @Inject
    private Event<FrontierRequestEvent> frontierRequestEvent;

    @Inject
    private Event<CSRFAttackAttemptEvent> csrfAttackAttemptEventEvent;

    @Inject
    private ConfigProvider configProvider;
    private Logger log = null;
    private Gson gson = null;

    private Base64.Decoder decoder = Base64.getDecoder();
    private SecureTokenUtil csrfTokenUtil = new SecureTokenUtil();

    private Object getParamValue(String frontier,FrontierMethod frontierMethod, MethodParam methodParam,
                                 Map<String,Object> uploadsMap,Map<String,Object> argsMap,
                                 HttpServletRequest request) throws FrontierCallException{

        Object paramValue = argsMap.get(methodParam.getName());
        if(paramValue==null&&!methodParam.isNullable())
            throw new MissingFrontierParamException(frontier,
                    frontierMethod.getName(),methodParam.getName());
        else if(paramValue==null)
            return null;

        if(!(methodParam.getType().isInstance(paramValue))&& paramValue instanceof Map){
            String paramJson = gson.toJson(paramValue);
            paramValue = gson.fromJson(paramJson, methodParam.getType());

        }else if(paramValue instanceof String){

            String strParamValue = (String) paramValue;
            if(strParamValue.startsWith("$$$upload")){

                Object uploadedFiles = getUploadedFiles(strParamValue,frontier,
                        frontierMethod,methodParam,uploadsMap,request);

                if(uploadedFiles!=null)
                    return uploadedFiles;

            }
        }

        return paramValue;

    }

    private Object getUploadedFiles(String strParamValue, String frontier, FrontierMethod frontierMethod,
                                   MethodParam methodParam, Map<String,Object> uploadsMap,
                                    HttpServletRequest request) throws FrontierCallException {

        String uploadName = strParamValue.substring(strParamValue.indexOf(":")+1,strParamValue.length());
        if(!uploadsMap.containsKey(uploadName))
            throw new FrontierCallException(frontier,methodParam.getName(),"The upload request data is corrupted");

        Double totalD = (Double) uploadsMap.get(uploadName);
        int total = totalD.intValue();
        if(total<1)
            throw new MissingFrontierParamException(frontier,frontierMethod.getName(),methodParam.getName());

        FileUpload[] files = new FileUpload[total];
        for(int i=0; i<total;i++){
            try {
                String partName = uploadName + "_file_" + i;
                Part part = request.getPart(partName);
                if (part == null)
                    throw new FrontierCallException(frontier, methodParam.getName(), "The upload request data is corrupted");
                files[i] = new FileUpload(part);
            }catch (IOException | ServletException ex){
                throw new FrontierCallException(frontier, methodParam.getName(), "Failed to decode uploaded content",ex);
            }
        }

        if(methodParam.getType().isArray())return files;
        else{

            if(files.length>1)
                throw new FrontierCallException(frontier,methodParam.getName(),"One file expected. "+files.length+" uploaded files found");
            return files[0];

        }

    }


    private Map matchParams(String frontier,FrontierMethod frontierMethod, RequestContext requestContext) throws FrontierCallException {

        HttpServletRequest req =  requestContext.getRequest();
        Map paramsMap =  new HashMap();

        if(req.getContentType().contains("multipart/form-data")){
            handleMultipartForm(req,frontier,frontierMethod,paramsMap);
            return paramsMap;
        }

        StringBuilder stringBuilder = new StringBuilder();

        try {

            Scanner scanner = new Scanner(requestContext.getRequest().getInputStream(),"UTF-8");
            while (scanner.hasNextLine())
                stringBuilder.append(scanner.nextLine());

        }catch (Throwable ex){

            log.error(String.format("Failed to match frontier parameters. class: %s, method: %s",frontier,frontierMethod.getName()),ex);
            return null;
        }

        parseParams(stringBuilder.toString(),frontierMethod,frontier,paramsMap);
        return paramsMap;

    }

    private void handleMultipartForm(HttpServletRequest req, String frontier, FrontierMethod frontierMethod,
                                     Map paramsMap) throws FrontierCallException{

        try {

            Part uploadsPart = req.getPart("$uploads");
            Scanner uploadsScanner = new Scanner(uploadsPart.getInputStream(),"UTF-8");
            StringBuilder uploadsJSONStringBuilder = new StringBuilder();
            while (uploadsScanner.hasNextLine())
                uploadsJSONStringBuilder.append(uploadsScanner.nextLine());

            Part argsPart = req.getPart("$args");
            Scanner argsScanner = new Scanner(argsPart.getInputStream(),"UTF-8");
            StringBuilder argsJSONStringBuilder = new StringBuilder();
            while (argsScanner.hasNextLine())
                argsJSONStringBuilder.append(argsScanner.nextLine());

            Gson gson = AppContext.createGson();
            Map<String,Object> uploadsMap = gson.fromJson(uploadsJSONStringBuilder.toString(),Map.class);
            Map<String,Object> argsMaps = gson.fromJson(argsJSONStringBuilder.toString(),Map.class);
            MethodParam methodParams[] = frontierMethod.getParams();

            for(MethodParam methodParam : methodParams)
                paramsMap.put(methodParam.getName(), getParamValue(frontier, frontierMethod, methodParam, uploadsMap, argsMaps, req));

        }catch (IOException | ServletException ex){

            throw new FrontierCallException(frontier,frontierMethod.getName(),"Failed to read parameters of frontier call with files attached",ex);

        }

    }

    private void parseParams(String params, FrontierMethod frontierMethod, String frontier, Map paramsMap)
            throws  FrontierCallException {

        Gson gson = appContext.getGsonBuilder().create();
        JsonElement jsonEl = new JsonParser().parse(params);
        JsonObject jsonObject = jsonEl.getAsJsonObject();
        MethodParam methodParams[] = frontierMethod.getParams();

        for(MethodParam methodParam : methodParams){
            JsonElement jsonElement = jsonObject.get(methodParam.getName());
            if(jsonElement==null&&!methodParam.isNullable())
                throw new MissingFrontierParamException(frontier,frontierMethod.getName(),methodParam.getName());
            else if(jsonElement==null){
                paramsMap.put(methodParam.getName(),
                        null);
            }

            Object paramValue = null;
            try {

                paramValue = gson.fromJson(jsonElement, methodParam.getType());

            }catch (Exception ex){
                paramValue = null;
            }

            if(paramValue==null&&!methodParam.isNullable())
                throw new InvalidFrontierParamException(frontier,frontierMethod.getName(),methodParam.getName());
            paramsMap.put(methodParam.getName(),
                    paramValue);

        }


    }


    private String[] getFrontierPair(RequestContext context){

        String route = context.getRouteUrl();
        route = route.substring(route.indexOf(':')+1,route.length());
        route = new String(decoder.decode(route));

        int slashIndex = route.indexOf('/');
        String className = route.substring(0,slashIndex);
        String methodName = route.substring(slashIndex+1,route.length());

        return new String[]{className,methodName};

    }


    private void handleOverride(FrontierRequestEvent event,FrontierInvoker invoker){

        if(event.valueOverriden())
            invoker.setReturnedObject(event.getOverrideValue());

    }

    private boolean executeFrontier(FrontierInvoker invoker, FrontierMethod method,
                                    FrontierClass clazz) throws Exception{

        FrontierRequestEvent req = new FrontierRequestEvent();
        req.setBefore();
        req.setMethod(method.getMethod());
        req.setClazz(clazz.getFrontierClazz());
        req.setArguments(invoker.getCallArguments());

        frontierRequestEvent.fire(req);

        if(req.wasInterrupted()) {
            handleOverride(req,invoker);
            return true;
        }

        boolean result  = invoker.invoke(req.getArguments());
        req.setAfter();
        frontierRequestEvent.fire(req);
        handleOverride(req,invoker);
        return result;

    }

    private boolean handleException(Exception ex, String invokedClass, String invokedMethod ){

        if(ex instanceof InvocationTargetException && ex.getCause() instanceof Exception)
            ex = (Exception) ex.getCause();

        log.error("An error occurred during frontier method invocation <"+invokedClass+"."+invokedMethod+">",ex);

        if(ex instanceof ConstraintViolationException){
            ConstraintViolationException violationException = (ConstraintViolationException) ex;
            handleConstraintViolation(violationException);
            return true;
        }

        requestContext.getResponse().setStatus(500);
        requestContext.getResponse().setContentType("text/json;charset=UTF8");
        requestContext.echo(serializeThrowable(ex));
        return true;

    }

    private String serializeThrowable(Exception ex){

        Throwable throwable = null;
        if(ex instanceof InvocationTargetException)
            throwable = ((InvocationTargetException)ex).getTargetException();
        else throwable = ex;

        Map exception = new HashMap<>();
        exception.put("type",ex.getClass().getSimpleName());

        JsonObject jsonObject = gson.toJsonTree(throwable).getAsJsonObject();
        jsonObject.remove("cause");
        jsonObject.remove("stackTrace");
        jsonObject.remove("suppressedExceptions");
        jsonObject.remove("target");
        exception.put("details",jsonObject);

        return gson.toJson(exception);

    }


    private void handleConstraintViolation(ConstraintViolationException violationException){

        Set<ConstraintViolation<?>> violationSet = violationException.getConstraintViolations();
        String[] messages = new String[violationSet.size()];

        int i = 0;
        for(ConstraintViolation violation: violationSet){
            messages[i] = violation.getMessage();
            i++;
        }

        Gson gson = appContext.getGsonBuilder().create();

        Map details = new HashMap<>();
        details.put("messages",messages);

        Map exception = new HashMap<>();
        exception.put("type",ConstraintViolationException.class.getSimpleName());
        exception.put("details",details);

        String resp = gson.toJson(exception);
        requestContext.getResponse().setStatus(500);
        requestContext.getResponse().setContentType("text/json;charset=UTF8");
        requestContext.echo(resp);

    }


    private void okInvocationResult(FrontierInvoker frontierInvoker,
                                    FrontierClass frontierClass, FrontierMethod frontierMethod) throws ResultConversionException{
        try {

            Gson gson = appContext.getGsonBuilder().create();
            Map map = new HashMap();
            Object returnedObject = frontierInvoker.getReturnedObject();
            map.put("result",returnedObject);

            if(frontEnd.gotLaterInvocations())
                map.put(HTMLizer.JS_INVOKABLES_KEY, frontEnd.getLaterInvocations());
            if(frontEnd.wasTemplateDataSet())
                map.put(HTMLizer.TEMPLATE_DATA_KEY,frontEnd.getTemplateData());

            String resp = gson.toJson(map);
            requestContext.getResponse().setContentType("text/json;charset=UTF8");
            requestContext.echo(resp);

        }catch (Throwable ex){

            throw new ResultConversionException(frontierClass.getClassName(),frontierMethod.getName(),ex);

        }

    }

    private Optional<Cookie> getCSRFCookie(HttpServletRequest request){
        String cookieName = Frontiers.Security.CrossSiteRequestForgery.Cookie.NAME;
        for(Cookie cookie: request.getCookies()){
            if(cookie.getName().equals(cookieName))
            return Optional.of(cookie);
        }
        LOGGER.warn("Cookie not present: "+cookieName);
        return Optional.empty();
    }

    private FrontierSecurityChecklist checkRequest(RequestContext requestContext){
        FrontierSecurityChecklist checklist = new FrontierSecurityChecklist();
        String requestOrigin = requestContext.getRequest().getHeader("Origin");
        if(requestOrigin!=null&&!requestOrigin.equals(appContext.getOrigin())){
            LOGGER.warn("CSRF Attack attempt detected. Not a valid origin: "+requestOrigin);
            csrfAttackAttemptEventEvent.fire(new CSRFAttackAttemptEvent());
            return checklist;
        }
        checklist.setOriginCheck(true);
        Optional<Cookie> csrfCookie = getCSRFCookie(requestContext.getRequest());
        if(!csrfCookie.isPresent()){
            LOGGER.warn("CSRF protection Cookie not found");
            return checklist;
        }
        checklist.setCsrfTokenPresent(true);
        if(!activeUser.getCsrfToken().equals(csrfCookie.get().getValue())){
            log.warn("CSRF Token did not match");
            String jwt = csrfCookie.get().getValue();
            if(!csrfTokenUtil.checkJwtToken(jwt)) {
                LOGGER.warn("CSRF Attack attempt detected. Not a valid token");
                csrfAttackAttemptEventEvent.fire(new CSRFAttackAttemptEvent());
                checklist.setCsrfTokenExpired(false);
                checklist.setCsrfTokenValid(false);
                return checklist;
            }else {
                checklist.setCsrfTokenValid(true);
                checklist.setCsrfTokenExpired(true);
                return checklist;
            }
        }
        checklist.setCsrfTokenValid(true);
        return checklist;
    }

    @PostConstruct
    private void handlerReady(){

        this.gson = AppContext.createGson();
        this.log = LoggerFactory.getLogger(FrontiersReqHandler.class);

    }


    private void emmitCSPHeaders(RequestContext context){
        HttpServletResponse response = context.getResponse();
        response.setHeader("Access-Control-Allow-Origin",appContext.getOrigin());
        response.setHeader("Vary","Origin");
    }

    private boolean securityCheck(RequestContext context){
        this.emmitCSPHeaders(context);
        FrontierSecurityChecklist securityChecklist = checkRequest(requestContext);
        HttpServletResponse response = requestContext.getResponse();
        if(!securityChecklist.isOriginCheck()||!securityChecklist.isCsrfTokenPresent()||!securityChecklist.isCsrfTokenValid()) {
            response.setStatus(403);
            return false;
        }else if(securityChecklist.isCsrfTokenExpired()){
            response.setStatus(419);
            return false;
        }
        return true;
    }

    @Override
    public boolean handle(RequestContext requestContext) throws ServletException, IOException {
        if(!securityCheck(requestContext))
            return true;
        String[] frontierPair = getFrontierPair(requestContext);
        String invokedClass = frontierPair[0];
        String invokedMethod = frontierPair[1];
        if(invokedClass==null||invokedMethod==null) {
            log.warn("Frontier class and method names not found");
            requestContext.getResponse().sendError(400);
            return true;
        }
        if(!frontierExists(invokedClass)) {
            log.warn("Frontier not found");
            return false;
        }
        FrontierClass frontierClass = getFrontier(invokedClass);
        if(!frontierClass.hasMethod(invokedMethod)) {
            log.warn("Frontier method not found");
            return false;
        }
        FrontierMethod frontierMethod = frontierClass.getMethod(invokedMethod);
        Map params = matchParams(invokedClass,frontierMethod, requestContext);
        FrontierInvoker frontierInvoker = new FrontierInvoker(frontierClass,frontierMethod,params);
        boolean invocationOK;
        try {
            if(!accessGranted(frontierClass.getObject().getClass(),frontierMethod.getMethod())){
                log.warn("Access to frontier method denied");
                requestContext.getResponse().sendError(403);
                return true;
            }
            invocationOK = executeFrontier(frontierInvoker,frontierMethod,frontierClass);
        }catch (Exception ex){
            return handleException(ex,invokedClass,invokedMethod);
        }
        if(invocationOK)
            okInvocationResult(frontierInvoker,frontierClass,frontierMethod);
        return invocationOK;
    }


}
