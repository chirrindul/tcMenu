/*
 * Copyright (c)  2016-2019 https://www.thecoderscorner.com (Nutricherry LTD).
 * This product is licensed under an Apache license, see the LICENSE file in the top-level directory.
 *
 */

package com.thecoderscorner.menu.editorui.generator.arduino;

import com.thecoderscorner.menu.domain.MenuItem;
import com.thecoderscorner.menu.domain.state.MenuTree;
import com.thecoderscorner.menu.domain.util.MenuItemHelper;
import com.thecoderscorner.menu.pluginapi.*;
import com.thecoderscorner.menu.pluginapi.model.BuildStructInitializer;
import com.thecoderscorner.menu.pluginapi.model.CodeVariableCppExtractor;
import com.thecoderscorner.menu.pluginapi.model.CodeVariableExtractor;
import com.thecoderscorner.menu.pluginapi.model.FunctionCallBuilder;
import com.thecoderscorner.menu.pluginapi.model.parameter.CodeConversionContext;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.thecoderscorner.menu.editorui.generator.arduino.MenuItemToEmbeddedGenerator.makeNameToVar;
import static com.thecoderscorner.menu.pluginapi.PluginFileDependency.PackagingType;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

public class ArduinoGenerator implements CodeGenerator {
    private final System.Logger logger = System.getLogger(getClass().getSimpleName());
    public static final String LINE_BREAK = System.getProperty("line.separator");
    public static final String TWO_LINES = LINE_BREAK + LINE_BREAK;

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault());

    private static final String COMMENT_HEADER = "/*\n" +
            "    The code in this file uses open source libraries provided by thecoderscorner" + LINE_BREAK + LINE_BREAK +
            "    DO NOT EDIT THIS FILE, IT WILL BE GENERATED EVERY TIME YOU USE THE UI DESIGNER" + LINE_BREAK +
            "    INSTEAD EITHER PUT CODE IN YOUR SKETCH OR CREATE ANOTHER SOURCE FILE." + LINE_BREAK + LINE_BREAK +
            "    All the variables you may need access to are marked extern in this file for easy" + LINE_BREAK +
            "    use elsewhere." + LINE_BREAK +
            " */" + LINE_BREAK + LINE_BREAK;

    private static final String HEADER_TOP = "#ifndef MENU_GENERATED_CODE_H" + LINE_BREAK +
            "#define MENU_GENERATED_CODE_H" + LINE_BREAK + LINE_BREAK;
    private final ArduinoLibraryInstaller installer;
    private final ArduinoSketchFileAdjuster arduinoSketchAdjuster;
    private final EmbeddedPlatform embeddedPlatform;

    private Consumer<String> uiLogger = null;

    public ArduinoGenerator(ArduinoSketchFileAdjuster adjuster,
                            ArduinoLibraryInstaller installer,
                            EmbeddedPlatform embeddedPlatform) {
        this.installer = installer;
        this.arduinoSketchAdjuster = adjuster;
        this.embeddedPlatform = embeddedPlatform;
    }

    @Override
    public boolean startConversion(Path directory, List<EmbeddedCodeCreator> codeGenerators, MenuTree menuTree,
                                   NameAndKey nameKey) {
        logLine("Starting Arduino generate: " + directory);

        boolean usesProgMem = embeddedPlatform.isUsesProgmem();

        // get the file names that we are going to modify.
        String inoFile = toSourceFile(directory, ".ino");
        String cppFile = toSourceFile(directory, "_menu.cpp");
        String headerFile = toSourceFile(directory, "_menu.h");
        String projectName = directory.getFileName().toString();

        var generators = new ArrayList<EmbeddedCodeCreator>();
        generators.add(new ArduinoGlobalsCreator(usesProgMem));
        generators.addAll(codeGenerators);

        try {
            // Prepare the generator by initialising all the structures ready for conversion.
            String root = getFirstMenuVariable(menuTree);
            var allProps = generators.stream().flatMap(gen -> gen.properties().stream()).collect(Collectors.toList());
            CodeVariableExtractor extractor = new CodeVariableCppExtractor(
                    new CodeConversionContext(embeddedPlatform, root, allProps), usesProgMem
            );

            Collection<BuildStructInitializer> menuStructure = generateMenusInOrder(menuTree);
            menuStructure = addNameAndKeyToStructure(menuStructure, nameKey);

            generators.forEach(gen -> gen.initialise(root));

            // generate the source by first generating the CPP and H for the menu definition and then
            // update the sketch. Also, if any plugins have changed, then update them.
            Map<MenuItem, CallbackRequirement> callbackFunctions = callBackFunctions(menuTree);
            generateHeaders(generators, headerFile, menuStructure, extractor, callbackFunctions);
            generateSource(generators, cppFile, menuStructure, projectName, extractor, menuTree, callbackFunctions);
            updateArduinoSketch(inoFile, projectName, callbackFunctions.values());
            addAnyRequiredPluginsToSketch(generators, directory);

            // do a couple of final checks and put out warnings if need be
            checkIfUpToDateWarningNeeded();
            checkIfLegacyFilesAreOnPath(directory);

            logLine("Process has completed, make sure the code in your IDE is up-to-date.");
            logLine("You may need to close the project and then re-open it to pick up changes..");
        } catch (Exception e) {
            logLine("ERROR during conversion---------------------------------------------");
            logLine("The conversion process has failed with an error: " + e.getMessage());
            logLine("A more complete error can be found in the log file in <Home>/.tcMenu");
            logger.log(ERROR, "Exception caught while converting code: ", e);
        }

        return true;
    }

    private List<FunctionCallBuilder> generateReadOnlyLocal(MenuTree menuTree) {
        var allFunctions = new ArrayList<FunctionCallBuilder>();

        allFunctions.addAll(menuTree.getAllMenuItems().stream().filter(MenuItem::isReadOnly)
                .map(item -> new FunctionCallBuilder()
                        .functionName("setReadOnly")
                        .objectName("menu" + makeNameToVar(item.getName()))
                        .param("true"))
                .collect(Collectors.toList())
        );

        allFunctions.addAll(menuTree.getAllMenuItems().stream().filter(MenuItem::isLocalOnly)
                .map(item -> new FunctionCallBuilder()
                        .functionName("setLocalOnly")
                        .objectName("menu" + makeNameToVar(item.getName()))
                        .param("true"))
                .collect(Collectors.toList())
        );

        return allFunctions;
    }

    private List<BuildStructInitializer> addNameAndKeyToStructure(Collection<BuildStructInitializer> menuStructure,
                                                                  NameAndKey nameKey) {
        var bsi = new BuildStructInitializer(MenuTree.ROOT, "applicationInfo", "ConnectorLocalInfo");
        var list = new ArrayList<>(menuStructure);
        list.add(bsi.addQuoted(nameKey.getName())
                .addQuoted(nameKey.getUuid())
                .progMemStruct().requiresExtern());
        return list;
    }

    private void checkIfLegacyFilesAreOnPath(Path directory) {
        if (Files.exists(Paths.get(toSourceFile(directory, ".h")))
                || Files.exists(Paths.get(toSourceFile(directory, ".cpp")))) {

            Path fileName = directory.getFileName();
            logLine("ERROR: OLD FILES FOUND !!!!!!!!!!==========================================");
            logLine("POTENTIAL COMPILE ERROR IN IDE - Non backward compatible change");
            logLine("From V1.2 onwards the source files containing menu definitions have changed");
            logLine("from " + fileName + ".h/.cpp to " + fileName + "_menu.h/_menu.cpp");
            logLine("To avoid errors in your IDE you will need to open the directory and remove");
            logLine("the files " + fileName + ".h/.cpp");
            logLine("Also remove the line #include <" + fileName + "_tcmenu.h> from your sketch");
            logLine("The directory is: " + directory);
            logLine("===========================================================================");
        }
    }

    private void generateSource(List<EmbeddedCodeCreator> generators, String cppFile,
                                Collection<BuildStructInitializer> menuStructure,
                                String projectName, CodeVariableExtractor extractor,
                                MenuTree menuTree,
                                Map<MenuItem, CallbackRequirement> callbackRequirements) throws TcMenuConversionException {

        try (Writer writer = new BufferedWriter(new FileWriter(cppFile))) {
            logLine("Writing out source CPP file: " + cppFile);

            writer.write(COMMENT_HEADER);

            writer.write("#include <tcMenu.h>");
            writer.write(LINE_BREAK);
            writer.write("#include \"" + projectName + "_menu.h\"");

            writer.write(TWO_LINES + "// Global variable declarations" + TWO_LINES);
            writer.write(extractor.mapVariables(
                    generators.stream().flatMap(ecc -> ecc.getVariables().stream()).collect(Collectors.toList())
            ));

            writer.write(TWO_LINES + "// Global Menu Item declarations" + TWO_LINES);
            StringBuilder toWrite = new StringBuilder(255);
            menuStructure.forEach(struct -> {
                var callback = callbackRequirements.get(struct.getMenuItem());
                if(callback != null) {
                    var srcList = callback.generateSource();
                    if(!srcList.isEmpty()) toWrite.append(String.join(LINE_BREAK, srcList));
                }
                toWrite.append(extractor.mapStructSource(struct));
            });
            writer.write(toWrite.toString());

            writer.write(TWO_LINES + "// Set up code" + TWO_LINES);
            writer.write("void setupMenu() {" + LINE_BREAK);
            writer.write(extractor.mapFunctions(
                    generators.stream().flatMap(ecc -> ecc.getFunctionCalls().stream()).collect(Collectors.toList())
            ));

            List<FunctionCallBuilder> readOnlyLocal = generateReadOnlyLocal(menuTree);
            if(!readOnlyLocal.isEmpty()) {
                writer.write(LINE_BREAK + LINE_BREAK + "    // Read only and local only function calls" + LINE_BREAK);
                writer.write(extractor.mapFunctions(readOnlyLocal));
            }

            writer.write(LINE_BREAK + "}" + LINE_BREAK);
            writer.write(LINE_BREAK);

            logLine("Finished processing source file.");

        } catch (Exception e) {
            logLine("Failed to generate CPP: " + e.getMessage());
            throw new TcMenuConversionException("Header Generation failed", e);
        }

    }

    private void generateHeaders(List<EmbeddedCodeCreator> embeddedCreators,
                                 String headerFile, Collection<BuildStructInitializer> menuStructure,
                                 CodeVariableExtractor extractor,
                                 Map<MenuItem, CallbackRequirement> callbackRequirements) throws TcMenuConversionException {
        try (Writer writer = new BufferedWriter(new FileWriter(headerFile))) {
            logLine("Writing out header file: " + headerFile);

            writer.write(COMMENT_HEADER);
            writer.write(HEADER_TOP);

            // first get a list of includes to add to the header file from the creators
            var includeList = embeddedCreators.stream().flatMap(g -> g.getIncludes().stream()).collect(Collectors.toList());

            // now add any extra headers needed for the menu structure items.
            includeList.addAll(menuStructure.stream()
                    .flatMap(s -> s.getHeaderRequirements().stream())
                    .collect(Collectors.toList()));

            // and write out the includes
            writer.write(extractor.mapIncludes(includeList));

            writer.write(LINE_BREAK + LINE_BREAK + "// all define statements needed" + LINE_BREAK);

            // now get all the #defines that we need to add.
            writer.write(extractor.mapDefines());

            writer.write(LINE_BREAK + LINE_BREAK + "// all variables that need exporting" + LINE_BREAK);

            // and put the exports in the file too
            writer.write(extractor.mapExports(
                    embeddedCreators.stream().flatMap(ecc -> ecc.getVariables().stream()).collect(Collectors.toList())
            ));
            writer.write(LINE_BREAK + LINE_BREAK + "// all menu item forward references." + LINE_BREAK);

            writer.write(menuStructure.stream()
                    .map(extractor::mapStructHeader)
                    .filter(item -> !item.isEmpty())
                    .collect(Collectors.joining(LINE_BREAK))
            );

            writer.write(TWO_LINES);

            writer.write("// Callback functions must always include CALLBACK_FUNCTION after the return type"
                    + LINE_BREAK + "#define CALLBACK_FUNCTION" + LINE_BREAK + LINE_BREAK);

            for (CallbackRequirement callback : callbackRequirements.values()) {
                writer.write(callback.generateHeader() + LINE_BREAK);
            }

            writer.write(LINE_BREAK + "void setupMenu();" + LINE_BREAK);
            writer.write(LINE_BREAK + "#endif // MENU_GENERATED_CODE_H" + LINE_BREAK);

            logLine("Finished processing header file.");
        } catch (Exception e) {
            logLine("Failed to generate header file: " + e.getMessage());
            throw new TcMenuConversionException("Header Generation failed", e);
        }
    }

    private void checkIfUpToDateWarningNeeded() {
        if (!installer.statusOfAllLibraries().isUpToDate()) {
            logLine("WARNING===============================================================");
            logLine("The embedded libraries are not up-to-date, build problems are likely");
            logLine("Select ROOT menu item and choose update libraries from the editor");
            logLine("WARNING===============================================================");
        }
    }

    private void updateArduinoSketch(String inoFile, String projectName,
                                     Collection<CallbackRequirement> callbackFunctions) throws TcMenuConversionException {
        logLine("Making adjustments to " + inoFile);

        try {
            arduinoSketchAdjuster.makeAdjustments(this::logLine, inoFile, projectName, callbackFunctions);
        } catch (IOException e) {
            logger.log(ERROR, "Sketch modification failed", e);
            throw new TcMenuConversionException("Could not modify sketch", e);
        }
    }

    private void addAnyRequiredPluginsToSketch(List<EmbeddedCodeCreator> generators, Path directory) throws TcMenuConversionException {
        logLine("Finding any required rendering / remote plugins to add to project");

        for (var gen : generators) {
            generatePluginsForCreator(gen, directory);
        }
    }

    private void generatePluginsForCreator(EmbeddedCodeCreator creator, Path directory) throws TcMenuConversionException {
        for (var file : creator.getRequiredFiles()) {
            try {

                // get the source (either from the plugin or from the tcMenu library)
                String fileNamePart;
                String fileData;
                if (file.getPackaging() == PackagingType.WITH_PLUGIN) {
                    String jarFileName = "META-INF/tcmenu/" + file.getFileName();
                    try (var sourceInputStream = creator.getClass().getClassLoader().getResourceAsStream(jarFileName)) {
                        if (sourceInputStream == null) throw new IOException("File not found: " + jarFileName);
                        fileData = new String(sourceInputStream.readAllBytes());
                        fileNamePart = Paths.get(file.getFileName()).getFileName().toString();
                    } catch (Exception e) {
                        throw new TcMenuConversionException("Unable to locate file in plugin: " + file, e);
                    }
                } else {
                    Path path = installer.findLibraryInstall("tcMenu")
                            .orElseThrow(IOException::new).resolve(file.getFileName());
                    fileData = new String(Files.readAllBytes(path));
                    fileNamePart = path.getFileName().toString();
                }

                // and apply the replacements one at a time
                for (Map.Entry<String, String> entry : file.getReplacements().entrySet()) {
                    fileData = fileData.replaceAll(entry.getKey(), entry.getValue());
                }

                // and copy into the destination
                Files.write(directory.resolve(fileNamePart), fileData.getBytes(), TRUNCATE_EXISTING, CREATE);
                logLine("Copied with replacement " + file);
            } catch (Exception e) {
                throw new TcMenuConversionException("Unexpected exception processing " + file, e);
            }
        }
    }

    @Override
    public void setLoggerFunction(Consumer<String> uiLogger) {
        this.uiLogger = uiLogger;
    }

    private String getFirstMenuVariable(MenuTree menuTree) {
        return menuTree.getMenuItems(MenuTree.ROOT).stream().findFirst()
                .map(menuItem -> "menu" + makeNameToVar(menuItem.getName()))
                .orElse("");
    }

    private Collection<BuildStructInitializer> generateMenusInOrder(MenuTree menuTree) {
        List<MenuItem> root = menuTree.getMenuItems(MenuTree.ROOT);
        List<List<BuildStructInitializer>> itemsInOrder = renderMenu(menuTree, root);
        Collections.reverse(itemsInOrder);
        return itemsInOrder.stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    private List<List<BuildStructInitializer>> renderMenu(MenuTree menuTree, Collection<MenuItem> itemsColl) {
        ArrayList<MenuItem> items = new ArrayList<>(itemsColl);
        List<List<BuildStructInitializer>> itemsInOrder = new ArrayList<>(100);
        for (int i = 0; i < items.size(); i++) {

            if (items.get(i).hasChildren()) {
                int nextIdx = i + 1;
                String nextSub = (nextIdx < items.size()) ? items.get(nextIdx).getName() : null;

                List<MenuItem> childItems = menuTree.getMenuItems(items.get(i));
                String nextChild = (!childItems.isEmpty()) ? childItems.get(0).getName() : null;
                itemsInOrder.add(MenuItemHelper.visitWithResult(items.get(i),
                        new MenuItemToEmbeddedGenerator(nextSub, nextChild)).orElse(Collections.emptyList()));
                itemsInOrder.addAll(renderMenu(menuTree, childItems));
            } else {
                int nextIdx = i + 1;
                String next = (nextIdx < items.size()) ? items.get(nextIdx).getName() : null;
                itemsInOrder.add(MenuItemHelper.visitWithResult(items.get(i),
                        new MenuItemToEmbeddedGenerator(next)).orElse(Collections.emptyList()));
            }
        }
        return itemsInOrder;
    }

    private Map<MenuItem, CallbackRequirement> callBackFunctions(MenuTree menuTree) {
        return menuTree.getAllSubMenus().stream()
                .flatMap(menuItem -> menuTree.getMenuItems(menuItem).stream())
                .filter(menuItem -> menuItem.getFunctionName() != null && !menuItem.getFunctionName().isEmpty())
                .map(i-> new CallbackRequirement(i.getFunctionName(), i))
                .collect(Collectors.toMap(CallbackRequirement::getCallbackItem, cr -> cr));
    }

    private String toSourceFile(Path directory, String ext) {
        Path file = directory.getFileName();
        return Paths.get(directory.toString(), file.toString() + ext).toString();
    }

    private void logLine(String s) {
        if (uiLogger != null) uiLogger.accept(DATE_TIME_FORMATTER.format(Instant.now()) + " - " + s);
        logger.log(INFO, s);
    }
}
