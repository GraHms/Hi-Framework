package org.emerjoin.hi.web.mvc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.emerjoin.hi.web.AppContext;
import org.emerjoin.hi.web.config.AppConfigurations;
import org.emerjoin.hi.web.events.TemplateTransformEvent;
import org.emerjoin.hi.web.events.ViewTransformEvent;
import org.emerjoin.hi.web.i18n.I18nContext;
import org.emerjoin.hi.web.i18n.I18nRuntime;
import org.emerjoin.hi.web.internal.ES5Library;
import org.emerjoin.hi.web.mvc.exceptions.ConversionFailedException;
import org.emerjoin.hi.web.mvc.exceptions.MalMarkedTemplateException;
import org.emerjoin.hi.web.mvc.exceptions.NoSuchTemplateException;
import org.emerjoin.hi.web.mvc.exceptions.TemplateException;
import org.emerjoin.hi.web.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.BASE64Encoder;

import javax.enterprise.event.Event;
import javax.enterprise.inject.spi.CDI;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Mario Junior.
 */
public class HTMLizer {

    private static HTMLizer instance = null;
    private static final String PREPARE_NEXT_VIEW = "Hi.$ui.js.createViewScope(vpath,context_vars,viewToLoad.html,false,false,false,false);";
    private static final String RUN_APP = "Hi.$angular.run();";
    private static final String BOOTSTRAP_ANGULAR = "angular.bootstrap(document, [\"hi\"]);";
    private static final String VIEW_NG = "<div id='app-view'></div>";

    public static final String JS_INVOKABLES_KEY = "$invoke";
    public static final String TEMPLATE_DATA_KEY = "$root";

    private GsonBuilder gsonBuilder = null;
    private RequestContext requestContext = null;
    private I18nContext i18nContext = null;
    private ActiveUser activeUser = null;
    private Map<String,String> cachedTemplates = Collections.synchronizedMap(new HashMap<>());

    private static Logger _log = LoggerFactory.getLogger(HTMLizer.class);

    private HTMLizer(String args){

        this.requestContext = CDI.current().select(RequestContext.class).get();

    }

    private String fetchTemplate(FrontEnd frontEnd, Event<TemplateTransformEvent> transformEvent) throws TemplateException {

        String templateName = frontEnd.getTemplate();

        URL templateURL = null;

        try {

            templateURL = requestContext.getServletContext().getResource("/" + templateName+".html");

        }catch (Exception ex){
            throw new NoSuchTemplateException(templateName);
        }

        if(templateURL==null)
            throw new NoSuchTemplateException(templateName);

        String templateFileContent = "";

        try {

            templateFileContent = Helper.readLines(templateURL.openStream(), null);

        }catch (Exception ex){
            throw new NoSuchTemplateException(templateName);
        }

        validateTemplate(templateName,templateFileContent);

        if(I18nRuntime.isReady())
            templateFileContent = I18nRuntime.get().translateTemplate(templateName,templateFileContent);
        TemplateTransformEvent templateTransformEvent =
                new TemplateTransformEvent(new Template(templateFileContent,templateName));
        transformEvent.fire(templateTransformEvent);
        templateFileContent = AppConfigurations.get().getTunings()
                .applySmartCaching(templateTransformEvent.getTemplate().getHtml(),false,templateName);


        return templateFileContent;

    }

    private void validateTemplate(String templateName, String templateFileContent) throws TemplateException {

        //Check the body tag, as its going to be replaced later in this same method
        int bodyCloseIndex = templateFileContent.indexOf("</body>");
        if(bodyCloseIndex==-1)
            throw new MalMarkedTemplateException(templateName,"token </body> could not be found. Body tag might not be present.");

        int headCloseIndex = templateFileContent.indexOf("</head>");
        if(headCloseIndex==-1)
            throw new MalMarkedTemplateException(templateName,"token </head> could not be found. Head tag might not be present");

        //Check the view_content div
        int viewContentDivByIdIndex = templateFileContent.indexOf("id=\"view_content\"");
        int viewContentDivByBrackets = templateFileContent.indexOf("{{view_content}}");

        if(viewContentDivByIdIndex==-1)
            throw new MalMarkedTemplateException(templateName,"no element with id property set to \"view_content\" could be found");

        if(viewContentDivByBrackets==-1)
            throw new MalMarkedTemplateException(templateName,"token {{view_content}} could not be found.");

    }


    private String javascriptVar(String name, String value){

        if(name==null)
            throw new NullPointerException("Variable name argument is null");

        if(name.trim().length()==0)
            throw new IllegalArgumentException("Variable name argument has an empty value");

        String declaration="var "+name+"="+value.toString()+";";
        return declaration;

    }

    private String makeJavascript(String code){

        return "<script>"+code+"</script>";

    }

    private String makeFunction(String name,String code){

        return "function "+name+"(){"+code+"}";

    }


    private String getViewInitSnippet(){

        return getNextClosureInformation()
                + getViewJS()+getControllerSetter();

    }


    private String getLoaderScript(FrontEnd frontEnd){

        StringBuilder scriptBuilder = new StringBuilder();
        scriptBuilder.append(" var appLang = "+ frontEnd.getLangDictionary()+";");
        scriptBuilder.append(" var appLangName = '"+ frontEnd.getLanguage()+"';");
        scriptBuilder.append("if(typeof loadApp==\"function\"){");
        scriptBuilder.append("if (window.addEventListener)");
        scriptBuilder.append(" window.addEventListener(\"load\", loadApp, false);");
        scriptBuilder.append(" else if (window.attachEvent)");
        scriptBuilder.append(" window.attachEvent(\"onload\",loadApp);");
        scriptBuilder.append("else window.onload = loadApp;");
        scriptBuilder.append(" }else{");
        scriptBuilder.append("if(typeof $ignition==\"function\"){");
        scriptBuilder.append(getViewInitSnippet());//New code
        scriptBuilder.append("angular.element(document).ready(function() {");
        scriptBuilder.append("$ignition();");
        scriptBuilder.append("});}}");
        return scriptBuilder.toString();

    }

    private String getAppData(){

        activeUser = CDI.current().select(ActiveUser.class).get();
        CharSequence http = "http://";
        CharSequence https = "https://";
        AppContext appContext = CDI.current().select(AppContext.class).get();
        Gson gson = AppContext.createGson();

        Map map = new HashMap();
        map.put("base_url", appContext.getBaseURL());
        map.put("base64_url", new BASE64Encoder().encode(appContext.getBaseURL().getBytes()));
        map.put("simple_base_url", appContext.getBaseURL().replace(http,"").replace(https,""));
        map.put("deployId",appContext.getDeployId());
        map.put("deployMode",appContext.getDeployMode().toString());
        map.put("eventsToken",activeUser.getEventsToken());
        return gson.toJson(map);

    }


    private String getNextClosureInformation(){

        String controller = requestContext.getData().get("controllerU").toString();
        String action = requestContext.getData().get("actionU").toString();
        String functionInvocation = "Hi.$nav.setNextControllerInfo(\""+controller+"\",\""+action+"\");";
        return functionInvocation;


    }

    private String getNextViewPath(){

        String controller = requestContext.getData().get("controllerU").toString();
        String action = requestContext.getData().get("actionU").toString();
        String functionInvocation = "var vpath = Hi.$nav.getViewPath(\""+controller+"\",\""+action+"\");";
        return functionInvocation;


    }

    private String getControllerSetter(){

        String controller = requestContext.getData().get("controllerU").toString();
        String action = requestContext.getData().get("actionU").toString();
        String functionInvocation = "Hi.$ui.js.setLoadedController(\""+controller+"\",\""+action+"\");";
        return functionInvocation;

    }

    private String getViewJS(){

        if(!requestContext.getData().containsKey("view_js"))
            return "Hi.view(function(_){})";

        return requestContext.getData().get("view_js").toString();

    }

    private boolean ignoreView(){

        return requestContext.getRequest()
                .getHeader("Ignore-View")!=null;

    }

    private boolean ignoreJs(){

        return requestContext.getRequest()
                .getHeader("Ignore-Js")!=null;

    }

    private void sendToClient(String result){

        requestContext.getResponse().setHeader("Cache-Control", "no-cache");
        requestContext.getResponse().setContentType("text/html;charset=UTF8");
        Helper.echo(result, requestContext);

    }

    private String ajaxProcess(FrontEnd frontEnd, String viewHTML,
                               Map<String,Object> viewData,Map route,
                               Controller controller) throws ConversionFailedException {

        Map<String,Map> actions = frontEnd.getLaterInvocations();
        if(!actions.isEmpty())
            viewData.put(JS_INVOKABLES_KEY,actions);

        Map map = new HashMap();

        if(!ignoreView()) map.put("view", viewHTML);
        if(!ignoreJs())   map.put("controller", getViewJS());
        map.put("data", viewData);
        map.put("route",route);
        map.put("response", 200);

        String resultResponse = null;
        Gson gson = gsonBuilder.create();

        try {

            resultResponse = gson.toJson(map);

        }catch (Exception ex){

            String actionMethod = requestContext.getData().get("action").toString();
            throw new ConversionFailedException(controller.getClass().getCanonicalName(),actionMethod,ex);

        }

        requestContext.getResponse().setContentType("text/json;charset=UTF8");
        requestContext.echo(resultResponse);
        return resultResponse;

    }


    private String normalProcess(FrontEnd frontEnd, String viewHTML, Map<String,Object> viewData,
                                 Map route, Controller controller, String loaderJSContent,
                                 String template) throws ConversionFailedException {

        viewData.put("$route",route);
        String loaderJavascript = makeJavascript(getLoaderScript(frontEnd));

        if(loaderJSContent==null) loaderJSContent="<!--Empty loader-->";
        else {
            String tokenToReplace = "//_place_init_code_here";
            String replacement = getViewInitSnippet();
            loaderJSContent = loaderJSContent.replace(tokenToReplace,replacement);
        }

        template = transformHeadAndBody(template,loaderJavascript,loaderJSContent);
        Gson gson = AppContext.createGson();
        String viewDataStr = null;

        try{

            viewDataStr = gson.toJson(viewData);

        }catch (Exception ex){
            String actionMethod = requestContext.getData().get("action").toString();
            throw new ConversionFailedException(controller.getClass().getCanonicalName(),actionMethod,ex);
        }

        Map html = new HashMap();
        html.put("html",viewHTML);
        String allJson = prepareIgnitionJS(viewDataStr,gson,html);

        allJson+="\n"+VIEW_NG;
        CharSequence toReplace = "{{view_content}}";
        CharSequence replaceBy = allJson;
        String processedResult =  template.replace(toReplace,replaceBy);

        this.sendToClient(processedResult);
        return processedResult;


    }


    private String prepareIgnitionJS(String viewDataStr, Gson gson, Map html){

        String appVariable = makeJavascript(javascriptVar("App",getAppData().toString()));
        String contextVarsVariable = makeJavascript(javascriptVar("context_vars",viewDataStr));
        String startupScript = makeJavascript(makeFunction("$startup",getNextViewPath()+PREPARE_NEXT_VIEW));
        String ignitionScript = makeJavascript(makeFunction("$ignition",RUN_APP+ BOOTSTRAP_ANGULAR));
        String viewToLoadVariable = makeJavascript(javascriptVar("viewToLoad",gson.toJson(html)));
        return appVariable+contextVarsVariable+viewToLoadVariable+startupScript+ignitionScript;

    }

    private String transformHeadAndBody(String template, String loaderJavascript, String loadedJSContent){

        CharSequence bodyCloseTag = "</body>";
        template = template.replace(bodyCloseTag,"</body>"+loaderJavascript);

        CharSequence headCloseTag = "</head>";
        CharSequence headClosedScript = "</head>"+makeJavascript(loadedJSContent);
        template = template.replace(headCloseTag,headClosedScript);
        return template;

    }

    public String process(Controller controller, boolean ignoreView,
                          boolean withViewMode, String viewMode,
                          Event<TemplateTransformEvent> templateTransformEventEvent,
                          Event<ViewTransformEvent> viewTransformEvent) throws TemplateException, ConversionFailedException {

        requestContext.getResponse().setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        FrontEnd frontEnd = CDI.current().select(FrontEnd.class).get();
        ES5Library es5Lib = CDI.current().select(ES5Library.class).get();
        String template = fetchTemplate(frontEnd,templateTransformEventEvent);
        String loaderJSContent= es5Lib.getHiLoaderJS();
        Map<String,Object> viewData = (Map<String,Object>) requestContext.getData().get(Controller.VIEW_DATA_KEY);

        String viewHTML = null;
        if(requestContext.getData().containsKey("view_content")){
            viewHTML = requestContext.getData().get("view_content").toString();
            if(viewHTML!=null&&!AppConfigurations.get().underDevelopment())
                viewHTML = AppConfigurations.get().getTunings().applySmartCaching(viewHTML, true,null);
        }

        if(viewHTML!=null){

             if(I18nRuntime.isReady()){
                 String viewId = requestContext.getData().get("controllerU").toString()+":"+requestContext.getData().get("actionU").toString();
                 if(withViewMode)
                     viewId+=viewMode;
                 I18nRuntime i18n = I18nRuntime.get();
                 viewHTML = i18n.translateView(viewId,viewHTML);
             }

            View v = new View(viewHTML);
            ViewTransformEvent event = new ViewTransformEvent(v);
            viewTransformEvent.fire(event);
            viewHTML = event.getTransformable().getHtml();

        }

        Map<String,Object> route = new HashMap<>();
        route.put("controller", requestContext.getData().get("controllerU").toString());
        route.put("action", requestContext.getData().get("actionU").toString());
        if(withViewMode)
            route.put("mode",viewMode);
        else route.put("mode",false);

        if(!viewData.containsKey(TEMPLATE_DATA_KEY))
            viewData.put(TEMPLATE_DATA_KEY, new HashMap<>());

        Map $templateDataMap = (Map) viewData.get("$root");
        viewData.put("$root",$templateDataMap);

        String mappedPath = "/"+requestContext.getRouteUrl();
        if(requestContext.getRequest().getHeader("Ignore-i18nMapping")==null){

            if(I18nRuntime.isReady()){

                Map<String,String> dictionary = new HashMap<>();

                I18nRuntime runtime = I18nRuntime.get();
                if(runtime.getConfiguration().isMappingsEnabled()) {

                    //Export the dictionary mapped to the current route
                    if (runtime.hasDictionary(mappedPath)) {
                        _log.debug(String.format("Exporting language dictionaries mapped to path : [%s]",mappedPath));
                        dictionary.putAll(runtime.getDictionary(mappedPath));
                    }else _log.warn(String.format("No dictionary mapped to path [%s]",mappedPath));

                }

                //Full bundle exportation disabled
                if(!runtime.getConfiguration().isExportLanguageBundle()){
                    dictionary.putAll(i18nContext.collect());
                    if(dictionary.size()>0)
                        _log.debug(String.format("Exporting %d dictionary terms",dictionary.size()));
                }

                viewData.put("$dictionary", dictionary);

            }

        }else{

            _log.debug(String.format("Ignoring I18N Mappings for [%s]",mappedPath));

        }


        if(requestContext.hasAjaxHeader()) /* AJAX REQUEST */
            return ajaxProcess(frontEnd,viewHTML,viewData,route,controller);
        return normalProcess(frontEnd,viewHTML,viewData,
                route,controller,loaderJSContent,template); /* NORMAL REQUEST */

    }

    public void setRequestContext(RequestContext requestContext){

        this.requestContext = requestContext;

    }

    public void setI18nContext(I18nContext i18nContext){

        this.i18nContext = i18nContext;

    }

    public void setGsonBuilder(GsonBuilder builder){

        this.gsonBuilder = builder;

    }


    public static HTMLizer getInstance(){

        return new HTMLizer(null);

    }


}
