package ftl.args

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.api.client.json.GenericJson
import com.google.api.client.json.JsonObjectParser
import com.google.api.client.util.Charsets
import com.google.cloud.ServiceOptions
import com.google.cloud.storage.BucketInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageClass
import com.google.cloud.storage.StorageOptions
import com.google.common.math.IntMath
import ftl.args.yml.IYmlMap
import ftl.config.FtlConstants
import ftl.config.FtlConstants.GCS_PREFIX
import ftl.config.FtlConstants.JSON_FACTORY
import ftl.config.FtlConstants.defaultCredentialPath
import ftl.gc.GcStorage
import ftl.util.Utils
import java.io.File
import java.io.IOException
import java.math.RoundingMode
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.nio.file.Paths
import java.util.HashSet
import java.util.regex.Pattern

object ArgsHelper {

    val yamlMapper: ObjectMapper by lazy { ObjectMapper(YAMLFactory()).registerModule(KotlinModule()) }

    fun mergeYmlMaps(vararg ymlMaps: IYmlMap): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        ymlMaps.map { it.map }
            .forEach { map ->
                map.forEach { (k, v) ->
                    result.merge(k, v) { a, b -> a + b }
                }
            }
        return result
    }

    fun assertFileExists(file: String, name: String) {
        if (!File(file).exists()) {
            Utils.fatalError("'$file' $name doesn't exist")
        }
    }

    fun evaluateFilePath(fileRegEx: String, name: String): String {
        var file = fileRegEx.trim().replaceFirst("^~", System.getProperty("user.home"))
        file = substituteEnvVars(file)
        val searchDirectoryPath = getSearchDirectoryPath(file)
        val filePaths = getAbsoluteFilePaths(searchDirectoryPath, file)
        if (filePaths.size > 1) {
            Utils.fatalError("$name multiple files found with expression: `$fileRegEx`: $filePaths")
        }
        return filePaths[0].toAbsolutePath().toString()
    }

    fun assertGcsFileExists(uri: String) {
        if (!uri.startsWith(GCS_PREFIX)) {
            throw IllegalArgumentException("must start with $GCS_PREFIX uri: $uri")
        }

        val gcsURI = URI.create(uri)
        val bucket = gcsURI.authority
        val path = gcsURI.path.drop(1) // Drop leading slash

        val blob = GcStorage.storage.get(bucket, path)

        if (blob == null) {
            Utils.fatalError("The file at '$uri' does not exist")
        }
    }

    fun validateTestMethods(
        testTargets: List<String>,
        validTestMethods: Collection<String>,
        from: String,
        skipValidation: Boolean = FtlConstants.useMock
    ) {
        val missingMethods = testTargets - validTestMethods

        if (!skipValidation && missingMethods.isNotEmpty()) Utils.fatalError("$from is missing methods: $missingMethods.\nValid methods:\n$validTestMethods")
        if (validTestMethods.isEmpty()) Utils.fatalError("$from has no tests")
    }

    fun calculateShards(
        testMethodsToShard: Collection<String>,
        testMethodsAlwaysRun: Collection<String>,
        testShards: Int
    ): List<List<String>> {
        val testShardMethods = testMethodsToShard.distinct().toMutableList()
        testShardMethods.removeAll(testMethodsAlwaysRun)

        val oneTestPerChunk = testShards == -1
        var chunkSize = IntMath.divide(testShardMethods.size, testShards, RoundingMode.UP)

        if (oneTestPerChunk || chunkSize < 1) {
            chunkSize = 1
        }

        val testShardChunks = testShardMethods.asSequence()
            .chunked(chunkSize)
            .map { testMethodsAlwaysRun + it }
            .toList()

        // Ensure we don't create more VMs than requested. VM count per run should be <= testShards
        if (!oneTestPerChunk && testShardChunks.size > testShards) {
            Utils.fatalError("Calculated chunks $testShardChunks is > requested $testShards testShards.")
        }
        if (testShardChunks.isEmpty()) Utils.fatalError("Failed to populate test shard chunks")

        return testShardChunks
    }

    fun getGcsBucket(projectId: String, resultsBucket: String): String {
        // com.google.cloud.storage.contrib.nio.testing.FakeStorageRpc doesn't support list
        // when testing, use a hard coded results bucket instead.
        if (FtlConstants.useMock) return resultsBucket
        // test lab supports using a special free storage bucket
        // because we don't have access to the root account, it won't show up in the storage list.
        if (resultsBucket.startsWith("test-lab-")) return resultsBucket

        val storage = StorageOptions.newBuilder().setProjectId(projectId).build().service
        val bucketLabel = mapOf(Pair("flank", ""))
        val storageLocation = "us-central1"

        val bucketListOption = Storage.BucketListOption.prefix(resultsBucket)
        val storageList = storage.list(bucketListOption).values?.map { it.name } ?: emptyList()
        val bucket = storageList.find { it == resultsBucket }
        if (bucket != null) return bucket

        return storage.create(
            BucketInfo.newBuilder(resultsBucket)
                .setStorageClass(StorageClass.REGIONAL)
                .setLocation(storageLocation)
                .setLabels(bucketLabel)
                .build()
        ).name
    }

    private fun serviceAccountProjectId(): String? {
        try {
            if (!defaultCredentialPath.toFile().exists()) return null

            return JsonObjectParser(JSON_FACTORY).parseAndClose(
                Files.newInputStream(defaultCredentialPath),
                Charsets.UTF_8,
                GenericJson::class.java)["project_id"] as String
        } catch (e: Exception) {
            println("Parsing $defaultCredentialPath failed:")
            println(e.printStackTrace())
        }

        return null
    }

    fun getDefaultProjectId(): String? {
        if (FtlConstants.useMock) return "mockProjectId"

        // Allow users control over projectId by checking using Google's logic first before falling back to JSON.
        return ServiceOptions.getDefaultProjectId() ?: serviceAccountProjectId()
    }

    internal fun getSearchDirectoryPath(path: String): String {
        var searchDirectoryPath = String()
        val pattern = "([^*]*/)"
        val matcher = Pattern.compile(pattern).matcher(path)
        if (matcher.find()) {
            searchDirectoryPath = matcher.group(1)
        }
        return searchDirectoryPath
    }

    internal fun substituteEnvVars(text: String): String {
        val sb = StringBuffer()
        // https://stackoverflow.com/a/2821201/2450315
        val pattern = "\\$([a-zA-Z_]{1,}[a-zA-Z0-9_]{0,})"
        val matcher = Pattern.compile(pattern).matcher(text)
        while (matcher.find()) {
            val varname = matcher.group(1)
            val envValue: String = System.getenv(varname) ?: ""
            matcher.appendReplacement(sb, envValue)
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    fun getAbsoluteFilePaths(searchDir: String, globPath: String): List<Path> {
        val maxDepth = if (globPath.contains("**")) Integer.MAX_VALUE else 1
        val glob = "glob:$globPath"
        val paths = ArrayList<Path>()
        val pathMatcher = FileSystems.getDefault().getPathMatcher(glob)

        Files.walkFileTree(Paths.get(searchDir), HashSet(), maxDepth, object : SimpleFileVisitor<Path>() {

            @Throws(IOException::class)
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                println(path)
                if (pathMatcher.matches(path)) {
                    paths.add(path)
                }
                return FileVisitResult.CONTINUE
            }

            @Throws(IOException::class)
            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                Utils.fatalError("'$file' doesn't exist")
                return FileVisitResult.CONTINUE
            }
        })
        return paths
    }
}
