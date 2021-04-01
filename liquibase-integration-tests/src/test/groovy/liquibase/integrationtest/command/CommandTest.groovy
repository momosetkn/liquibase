package liquibase.integrationtest.command

import liquibase.CatalogAndSchema
import liquibase.Scope
import liquibase.changelog.ChangeLogParameters
import liquibase.changelog.DatabaseChangeLog
import liquibase.command.CommandScope
import liquibase.database.Database
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.hub.HubService
import liquibase.hub.core.MockHubService
import liquibase.integrationtest.CustomTestSetup
import liquibase.integrationtest.TestDatabaseConnections
import liquibase.integrationtest.TestFilter
import liquibase.integrationtest.TestSetup
import liquibase.parser.ChangeLogParser
import liquibase.parser.ChangeLogParserFactory
import liquibase.test.JUnitResourceAccessor
import liquibase.util.FileUtil
import liquibase.util.StringUtil
import spock.lang.Specification
import spock.lang.Unroll

import java.util.regex.Pattern

import static org.junit.Assume.assumeTrue
import static org.spockframework.util.Assert.fail

class CommandTest extends Specification {

    @Unroll("Run {db:#specPermutation.databaseName,command:#specPermutation.spec._description}")
    def "run spec"() {
        setup:
        assumeTrue("Skipping test: " + specPermutation.connectionStatus.errorMessage, specPermutation.connectionStatus.connection != null)

        def spec = specPermutation.spec

        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(specPermutation.connectionStatus.connection))

        String defaultSchemaName = database.getDefaultSchemaName()
        CatalogAndSchema[] catalogAndSchemas = new CatalogAndSchema[1]
        catalogAndSchemas[0] = new CatalogAndSchema(null, defaultSchemaName)
        database.dropDatabaseObjects(catalogAndSchemas[0])

        List expectedOutputChecks = new ArrayList()
        if (spec._expectedOutput instanceof List) {
            expectedOutputChecks.addAll(spec._expectedOutput)
        } else {
            expectedOutputChecks.add(spec._expectedOutput)
        }

        when:
        def commandScope
        try {
            commandScope = new CommandScope(spec._command as String[])
        }
        catch (Throwable e) {
            if (spec._expectedException != null) {
                assert e.class == spec._expectedException
            }
            throw new RuntimeException(e)
        }
        assert commandScope != null
        def outputStream = new ByteArrayOutputStream()

        commandScope.addArgumentValue("database", database)
        commandScope.addArgumentValue("url", database.getConnection().getURL())
        commandScope.addArgumentValue("schemas", catalogAndSchemas)
        commandScope.addArgumentValue("logLevel", "FINE")
        commandScope.setOutput(outputStream)

        String changeLogFile = null
        if (spec._setup != null) {
            for (def setup : spec._setup) {
                setup.setup(specPermutation.connectionStatus)
                if (setup.getChangeLogFile() != null) {
                    changeLogFile = setup.getChangeLogFile()
                }
            }
        }
        if (spec._customSetup != null) {
            for (def customSetup : spec._customSetup) {
                customSetup.customSetup(specPermutation.connectionStatus, commandScope)
            }
        }

        if (changeLogFile != null) {
            commandScope.addArgumentValue("changeLogFile", changeLogFile)
            if (spec._needDatabaseChangeLog) {
              addDatabaseChangeLogToScope(changeLogFile, commandScope)
            }
        }
        if (spec._arguments != null) {
            spec._arguments.each { name, value ->
                Object objValue = (Object) value
                commandScope.addArgumentValue(name, objValue)
            }
        }
        def setupScopeId = Scope.enter([
                ("liquibase.plugin." + HubService.name): MockHubService,
        ])

        def results = commandScope.execute()

        Scope.exit(setupScopeId)

        def fullOutput = StringUtil.standardizeLineEndings(StringUtil.trimToEmpty(outputStream.toString()))

        for (def returnedResult : results.getResults().entrySet()) {
            def expectedValue = String.valueOf(spec._expectedResults.get(returnedResult.getKey()))
            def seenValue = String.valueOf(returnedResult.getValue())

            assert expectedValue != "null": "No expectedResult for returned result '" + returnedResult.getKey() + "' of: " + seenValue
            assert seenValue == expectedValue
        }

        then:

//        def e = thrown(spec.expectedException)

        for (def expectedOutputCheck : expectedOutputChecks) {
            if (expectedOutputCheck instanceof String) {
                assert fullOutput.contains(StringUtil.standardizeLineEndings(StringUtil.trimToEmpty(expectedOutputCheck))): """
Command output:
-----------------------------------------
${fullOutput}
-----------------------------------------
Did not contain:
-----------------------------------------
${expectedOutputCheck}
-----------------------------------------
""".trim()
            } else if (expectedOutputCheck instanceof Pattern) {
                assert expectedOutputCheck.matcher(fullOutput.replace("\r", "").trim()).find(): """
Command output:
-----------------------------------------
${fullOutput}
-----------------------------------------
Did not match regexp:
-----------------------------------------
${expectedOutputCheck.toString()}
-----------------------------------------
""".trim()
            } else {
                fail "Unknown expectedOutput check: ${expectedOutputCheck.class.name}"
            }
        }


        where:
        specPermutation << collectSpecPermutations()
    }

    static void addDatabaseChangeLogToScope(String changeLogFile, CommandScope commandScope) {
        //
        // Create a temporary changelog file
        //
        URL url = Thread.currentThread().getContextClassLoader().getResource(changeLogFile)
        File f = new File(url.toURI())
        String contents = FileUtil.getContents(f)
        File outputFile = File.createTempFile("changeLog-", ".xml", new File("target/test-classes"))
        FileUtil.write(contents, outputFile)
        changeLogFile = outputFile.getName()
        commandScope.addArgumentValue("changeLogFile", changeLogFile)

        //
        // Parse the file to get the DatabaseChangeLog and add it to the CommandScope
        //
        JUnitResourceAccessor resourceAccessor = new JUnitResourceAccessor()
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changeLogFile, resourceAccessor)
        ChangeLogParameters changeLogParameters = new ChangeLogParameters()
        DatabaseChangeLog databaseChangeLog = parser.parse(changeLogFile, changeLogParameters, resourceAccessor)
        commandScope.addArgumentValue("changeLog", databaseChangeLog)
    }

    static List<File> collectSpecFiles() {
        def returnFiles = new ArrayList<File>()

        ("src/test/resources/liquibase/integrationtest/command/" as File).eachFileRecurse {
            if (it.name.endsWith("test.groovy")) {
                returnFiles.add(it)
            }
        }

        return returnFiles
    }

    static List<Spec> collectSpecs() {
        def loader = new GroovyClassLoader()
        def returnList = new ArrayList<Spec>()

        for (def specFile : collectSpecFiles()) {
            Class specClass

            try {
                specClass = loader.parseClass(specFile)

                for (def specObj : ((Script) specClass.newInstance()).run()) {
                    if (!specObj instanceof Spec) {
                        fail "$specFile must contain an array of LiquibaseCommandTest.Spec objects"
                    }

                    def spec = (Spec) specObj

                    if (spec._description == null) {
                        spec._description = StringUtil.join((Collection) spec._command, " ")
                    }

                    spec.validate()

                    returnList.add(spec)
                }
            } catch (Throwable e) {
                throw new RuntimeException("Error parsing ${specFile}: ${e.message}", e)
            }
        }

        return returnList
    }

    static List<SpecPermutation> collectSpecPermutations() {
        def returnList = new ArrayList<SpecPermutation>()
        def allSpecs = collectSpecs()

        for (Database database : DatabaseFactory.getInstance().getImplementedDatabases()) {
            for (Spec spec : allSpecs) {
                def permutation = new SpecPermutation(
                        spec: spec,
                        databaseName: database.shortName,
                )

                if (!permutation.shouldRun()) {
                    continue
                }

                permutation.connectionStatus = TestDatabaseConnections.getInstance().getConnection(database.shortName)
                returnList.add(permutation)
            }
        }

        return returnList
    }

    static commandTests(Spec... specs) {
        return specs
    }

    static Spec run(@DelegatesTo(Spec) Closure cl) {
        def spec = new Spec()
        def code = cl.rehydrate(spec, this, this)
        code.resolveStrategy = Closure.DELEGATE_ONLY
        code()
    }

    static class Spec {

        private String _description
        private List<String> _command
        private Map<String, Object> _arguments = new HashMap<>()
        private List<TestSetup> _setup
        private List <CustomTestSetup> _customSetup
        private boolean _needDatabaseChangeLog
        private List<Object> _expectedOutput
        private Map<String, Object> _expectedResults = new HashMap<>()
        private Class<Throwable> _expectedException

        /**
         * Description of this test for reporting purposes.
         * If not set, one will be generated for you.
         */
        Spec description(String description) {
            this._description = description
            this
        }

        /**
         * Command to execute
         */
        Spec command(String... command) {
            this._command = command
            this
        }

        /**
         * Arguments to command as key/value pairs
         */
        Spec arguments(Map<String, Object> arguments) {
            this._arguments = arguments
            this
        }

        Spec setup(TestSetup... setup) {
            this._setup = setup
            this
        }

        Spec customSetup(CustomTestSetup... customSetup) {
            this._customSetup = customSetup
            this
        }

        /**
         * Checks for the command output.
         * <li>If a string, assert that the output CONTAINS the string.
         * <li>If a regexp, assert that the regexp FINDs the string.
         */
        Spec expectedOutput(Object... output) {
            this._expectedOutput = output
            this
        }


        Spec expectedResults(Map<String, Object> results) {
            this._expectedResults = results
            this
        }

        Spec expectedException(Class<Throwable> exception) {
            this._expectedException = exception
            this
        }

        Spec needDatabaseChangeLog(boolean needDatabaseChangeLog) {
            this._needDatabaseChangeLog = needDatabaseChangeLog
            this
        }

        void validate() {
            if (_command == null || _command.size() == 0) {
                throw new IllegalArgumentException("'command' is required")
            }
        }
    }

    private static class SpecPermutation {
        Spec spec
        String databaseName
        TestDatabaseConnections.ConnectionStatus connectionStatus
        String changeLogFile

        boolean shouldRun() {
            def filter = TestFilter.getInstance()

            return filter.shouldRun(TestFilter.DB, databaseName) &&
                    filter.shouldRun("command", spec._command.get(0))
        }
    }
}
