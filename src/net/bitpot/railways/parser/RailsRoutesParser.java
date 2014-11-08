package net.bitpot.railways.parser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import net.bitpot.railways.models.RailsEngine;
import net.bitpot.railways.models.Route;
import net.bitpot.railways.models.RouteList;
import net.bitpot.railways.models.routes.RequestMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class parses text and retrieves RouteNode
 */
public class RailsRoutesParser extends AbstractRoutesParser {
    @SuppressWarnings("unused")
    private static Logger log = Logger.getInstance(RailsRoutesParser.class.getName());

    // Errors
    public static final int NO_ERRORS = 0;
    public static final int ERROR_GENERAL = -1;
    public static final int ERROR_RAKE_TASK_NOT_FOUND = -2;


    private static final Pattern LINE_PATTERN = Pattern.compile("^\\s*([a-z0-9_]+)?\\s*([A-Z|]+)?\\s+(\\S+?)\\s+(.+?)$");
    private static final Pattern ACTION_PATTERN = Pattern.compile(":action\\s*=>\\s*['\"](.+?)['\"]");
    private static final Pattern CONTROLLER_PATTERN = Pattern.compile(":controller\\s*=>\\s*['\"](.+?)['\"]");
    private static final Pattern REQUIREMENTS_PATTERN = Pattern.compile("(\\{.+?\\}\\s*$)");
    private static final Pattern REQUIREMENT_PATTERN = Pattern.compile(":([a-zA-Z0-9_]\\w*)\\s*=>\\s*(.+?)[,]");

    private static final String EXCEPTION_REGEX = "(?s)rake aborted!\\s*(.+?)Tasks:";

    // Will capture both {:to => Test::Server} and Test::Server.
    private static final Pattern RACK_CONTROLLER_PATTERN = Pattern.compile("([A-Z_][A-Za-z0-9_:/]+)");

    public static final Pattern HEADER_LINE = Pattern.compile("^\\s*Prefix\\s+Verb");
    public static final Pattern ENGINE_ROUTES_HEADER_LINE = Pattern.compile("^Routes for ([a-zA-Z0-9:_]+):");

    private String stacktrace;

    //private final Project project;
    private Module myModule;
    private int errorCode;

    private List<RailsEngine> mountedEngines = new ArrayList<RailsEngine>();


    public RailsRoutesParser() {
        this(null);
    }


    public RailsRoutesParser(@Nullable Module module) {
        myModule = module;
        clearErrors();
    }


    public void clearErrors() {
        stacktrace = "";
        errorCode = NO_ERRORS;
    }


    public RouteList parse(String stdOut, @Nullable String stdErr) {
        parseErrors(stdErr);

        return parse(new ByteArrayInputStream(stdOut.getBytes()));
    }


    @Override
    public RouteList parse(InputStream stream) {
        try {
            RouteList routes = new RouteList();

            DataInputStream ds = new DataInputStream(stream);
            BufferedReader br = new BufferedReader(new InputStreamReader(ds));

            String strLine;
            List<Route> routeList;

            //Read File Line By Line
            while ((strLine = br.readLine()) != null) {
                if (parseSpecialLine(strLine))
                    continue;

                routeList = parseLine(strLine);
                if (routeList != null) {
                    routes.addAll(routeList);

                    addRakeEngineIfPresent(routeList);
                }

            }

            return routes;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }


    /**
     * Adds rake engine to the list of parsed engines.
     * @param routeList Route list parsed from a line.
     */
    private void addRakeEngineIfPresent(@NotNull List<Route> routeList) {
        if (routeList.size() != 1)
            return;

        Route route = routeList.get(0);
        if (route.getType() == Route.MOUNTED) {
            mountedEngines.add(new RailsEngine(route.getControllerMethodName(),
                    route.getPath()));
        }
    }


    /**
     * Parses special lines, such as Header, or line with information about
     * routes engine. Returns true if it matches special line pattern and was
     * successfully parsed, false otherwise.
     *
     * @param line Line from rake routes.
     * @return true if line is a special line and was parsed successfully.
     */
    public boolean parseSpecialLine(String line) {
        Matcher matcher = HEADER_LINE.matcher(line);
        if (matcher.find())
            return true;

        matcher = ENGINE_ROUTES_HEADER_LINE.matcher(line);
        if (matcher.find()) {

            return true;
        }

        return false;
    }


    /**
     * Parses standard line from the output of rake 'routes' task. If this line contains route information,
     * new Route will be created and its fields set with appropriate parsed values.
     *
     * @param line Line from 'rake routes' output
     * @return Route object, if line contains route information, null if parsing failed.
     */
    public List<Route> parseLine(String line) {
        // 1. Break line into 3 groups - [name]+[verb], path, conditions(action, controller)
        Matcher groups = LINE_PATTERN.matcher(line);

        if (groups.matches()) {
            String routeController, routeAction;
            String routeName = getGroup(groups, 1);
            String routePath = getGroup(groups, 3);
            String conditions = getGroup(groups, 4);
            String[] actionInfo = conditions.split("#", 2);

            // Process new format of output: 'controller#action'
            if (actionInfo.length == 2) {
                routeController = actionInfo[0];

                // In this case second part can contain additional requirements. Example:
                // "index {:user_agent => /something/}"
                routeAction = extractRouteRequirements(actionInfo[1]);
            } else {
                // Older format - all route requirements are specified in ruby hash:
                // {:controller => 'users', :action => 'index'}
                routeController = captureGroup(CONTROLLER_PATTERN, conditions);
                routeAction = captureGroup(ACTION_PATTERN, conditions);

                if (routeController.isEmpty())
                    routeController = captureGroup(RACK_CONTROLLER_PATTERN, conditions);
            }


            // We can have several request methods here: "GET|POST"
            String[] requestMethods = getGroup(groups, 2).split("\\|");
            List<Route> result = new ArrayList<Route>();

            for (String requestMethodName : requestMethods) {
                Route route = new Route(myModule,
                        RequestMethod.get(requestMethodName), routePath,
                        routeController, routeAction, routeName);

                if (route.isValid())
                    result.add(route);
            }

            return result;
        } else {
            // TODO: string not matched. Should log this error somehow.
        }

        return null;
    }


    /**
     * Extracts requirements from second part and fills route information.
     *
     * @param actionWithReq Action with possible requirements part
     * @return Route action name without requirements.
     */
    private String extractRouteRequirements(String actionWithReq) {
        String requirements = captureGroup(REQUIREMENTS_PATTERN, actionWithReq);

        // Return action without requirements
        return actionWithReq.substring(0, actionWithReq.length() - requirements.length()).trim();
    }


    @NotNull
    private String getGroup(Matcher matcher, int groupNum) {
        String s = matcher.group(groupNum);
        return (s != null) ? s.trim() : "";
    }


    /**
     * Captures first group in subject
     *
     * @param pattern Regex pattern
     * @param subject Subject string
     * @return Captured group or an empty string.
     */
    private String captureGroup(Pattern pattern, String subject) {
        Matcher m = pattern.matcher(subject);
        if (m.find())
            return m.group(1);

        return "";
    }


    public void parseErrors(@Nullable String stdErr) {
        clearErrors();

        if (stdErr == null)
            return;

        // Remove all rake messages that go to stdErr. Those messages start with "**".
        String cleanStdErr = stdErr.replaceAll("(?m)^\\*\\*.*$", "").trim();
        if (cleanStdErr.equals(""))
            return;

        if (cleanStdErr.contains("Don't know how to"))
            errorCode = ERROR_RAKE_TASK_NOT_FOUND;
        else {
            errorCode = ERROR_GENERAL;
            // Remove unnecessary text if exception was thrown after rake sent several messages to stderr.
            stacktrace = cleanStdErr.replaceAll(EXCEPTION_REGEX, "$1");
        }
    }


    public String getErrorStacktrace() {
        return stacktrace;
    }


    public boolean isErrorReported() {
        return errorCode != NO_ERRORS;
    }


    public int getErrorCode() {
        return errorCode;
    }


    public List<RailsEngine> getMountedEngines() {
        return mountedEngines;
    }
}
