package org.broadinstitute.hellbender.utils.help;

import org.broadinstitute.barclay.argparser.CommandLineArgumentParser;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.argparser.NamedArgumentDefinition;
import org.broadinstitute.barclay.help.DefaultDocWorkUnitHandler;
import org.broadinstitute.barclay.help.DocWorkUnit;
import org.broadinstitute.barclay.help.HelpDoclet;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The GATK WDL work unit handler class that is the companion to GATKWDLDoclet.
 *
 * NOTE: Methods in this class are intended to be called by Gradle/Javadoc only, and should not be called
 * by methods that are used by the GATK runtime, as this class assumes a dependency on com.sun.javadoc classes
 * which may not be present.
 */
public class GATKWDLWorkUnitHandler extends DefaultDocWorkUnitHandler {

    private final static String GATK_FREEMARKER_TEMPLATE_NAME = "toolWDLTemplate.wdl";

    public GATKWDLWorkUnitHandler(final HelpDoclet doclet) {
        super(doclet);
    }

    /**
     * @param workUnit the classdoc object being processed
     * @return the name of a the freemarker template to be used for the class being documented.
     * Must reside in the folder passed to the Barclay Doclet via the "-settings-dir" parameter to
     * Javadoc.
     */
    @Override
    public String getTemplateName(final DocWorkUnit workUnit) { return GATK_FREEMARKER_TEMPLATE_NAME; }

    /**
     * Add information about all of the arguments available to toProcess root
     */
    protected void addCommandLineArgumentBindings(final DocWorkUnit currentWorkUnit, final CommandLineArgumentParser clp) {
        super.addCommandLineArgumentBindings(currentWorkUnit, clp);

        final List<NamedArgumentDefinition> argDefs = clp.getNamedArgumentDefinitions();
        //argDefs.forEach(ad -> System.out.println(ad.getUnderlyingField()));

//        final Map currentWorkUnit.getRootMap();
//        processPositionalArguments(clp, argMap);
//        clp.getNamedArgumentDefinitions().stream().forEach(argDef -> processNamedArgument(currentWorkUnit, argMap, argDef));
    }

    /**
      * Populate a FreeMarker map with attributes of an argument
      *
      * @param argBindings
      * @param argDef
      * @param fieldCommentText
      * @return
      */
    @Override
    protected String processNamedArgument(
            final Map<String, Object> argBindings,
            final NamedArgumentDefinition argDef,
            final String fieldCommentText) {
        final String argKind = super.processNamedArgument(argBindings, argDef, fieldCommentText);

        // now replace the java type with the appropriate wdl type
        final String javaType = (String) argBindings.get("type");
        final String wdlType = javaTypeToWDLType(javaType);

        argBindings.put("type", wdlType);
        //System.out.println(String.format("Converting %s to %s", javaType, wdlType));

        // and replace the actual argument name with a wdl-friendly name
        // "input" and "output" are reserved words in WDL and can't be used for arg names, so use the
        // arg's shortName/synonym if there is one
        final String actualArgName = (String) argBindings.get("name");
        String wdlName = actualArgName;

        //TODO: Remove the "--" from Barclay!
        if (actualArgName.equals("--output") || actualArgName.equals("--input")) {
            if (argBindings.get("synonyms") != null) {
                //System.out.println(String.format("Converting %s to -%s", actualArgName, argBindings.get("synonyms")));
                wdlName = "-" + argBindings.get("synonyms").toString();
            } else {
                //TODO: include out the tool context in the message
                throw new RuntimeException(String.format(
                        "Can't generate WDL for argument named %s (which is a WDL reserved word)",
                        actualArgName));
            }
        }

        // WDL doesn't accept "-", so change to non-kebab w/underscore
        wdlName = wdlName.substring(2).replace("-", "_");
        argBindings.put("name", "--" + wdlName);

        //generateWDLStructs();

        //argBindings.entrySet().forEach(e -> System.out.println(e.getKey() + ":" + e.getValue()));
        //System.out.println(String.format("Converting %s to %s\n", actualArgName, wdlName));
        return argKind;
    }

    // Convert java arg type to a WDL friendly type
    private String javaTypeToWDLType(final String javaType) {
        String wdlType = javaType;
        if (javaType.contains("boolean")) {
            wdlType = javaType.replace("boolean", "Boolean");
        } else if (javaType.contains("int")) {
            wdlType = javaType.replace("int", "Int");
        } else if (javaType.contains("Integer")) {
            wdlType = javaType.replace("Integer", "Int");
        } else if (javaType.contains("Long")) {
            wdlType = javaType.replace("Long", "Int");
        } else if (javaType.contains("long")) {
            //TODO: WDL has no long type
            wdlType = javaType.replace("long", "Int");
        } else if (javaType.contains("FeatureInput")) {
            //TODO: need to handle tags
            wdlType = javaType.replaceFirst("FeatureInput\\[[a-zA-Z0-9?]+\\]","File");
        } else if (javaType.contains("GATKPathSpecifier")) {
            //TODO: need to handle tags
            wdlType = "File";
        } else if (javaType.contains("double") || javaType.contains("float")) {
            wdlType = "Float";
        }

        if (wdlType.startsWith("List")) {
            wdlType = wdlType.replace("List", "Array");
        } else if (wdlType.startsWith("ArrayList")){
            wdlType = wdlType.replace("ArrayList", "Array");
        } else if (wdlType.startsWith("Set")){
            wdlType = wdlType.replace("Set", "Array");
        }

        return wdlType;
    }

    static final Pattern ARRAY_TYPE_REGEX =  Pattern.compile("Array\\[(.*)\\]");

    final void emitStructs(final DocWorkUnit currentWorkUnit, final Map<String, Object> m) {
        String wdlType = (String) m.get("type");
        String actualType = wdlType;
        final Matcher matcher = ARRAY_TYPE_REGEX.matcher(wdlType);
        if (matcher != null && matcher.matches() && matcher.group(1) != null) {
            actualType = matcher.group(1);
        }

        if (!isWDLPrimitiveType(actualType)) {
            // assume we have an enum, so generate a struct for the type
            wdlType = wdlType.replace(wdlType, "String");
            m.put("type", wdlType);
            System.out.println("Dangling type: " + actualType + " (wdltype) " + wdlType + ":" + currentWorkUnit.getClazz());
        }
    }

    private boolean isWDLPrimitiveType(final String wdlType) {
        return wdlType.equals("String") ||
                wdlType.equals("Boolean") ||
                wdlType.equals("Int") ||
                wdlType.equals("File") ||
                wdlType.equals("Float");
    }

    /**
     * Add any custom freemarker bindings discovered via custom javadoc tags. Subclasses can override this to
     * provide additional custom bindings.
     *
     * @param currentWorkUnit the work unit for the feature being documented
     */
    @Override
    protected void addCustomBindings(final DocWorkUnit currentWorkUnit) {
        super.addCustomBindings(currentWorkUnit);

        // emit any structs for args that are enums so they'll be defined for WDL
//        currentWorkUnit.getClazz();

        @SuppressWarnings("unchecked")
        final Map<String, List<Map<String, Object>>> argMap =
                (Map<String, List<Map<String, Object>>>) currentWorkUnit.getProperty("arguments");
        argMap.forEach((ak, alist) -> {
            alist.forEach(m -> { emitStructs(currentWorkUnit, m); });
        });

        // Picard tools use the summary line for the long overview section, so extract that
        // from Picard tools only, and put it in the freemarker map.
        Class<?> toolClass = currentWorkUnit.getClazz();
        if (picard.cmdline.CommandLineProgram.class.isAssignableFrom(toolClass)) {
            final CommandLineProgramProperties clpProperties = currentWorkUnit.getCommandLineProperties();
            currentWorkUnit.setProperty("picardsummary", clpProperties.summary());
        }
    }

}
