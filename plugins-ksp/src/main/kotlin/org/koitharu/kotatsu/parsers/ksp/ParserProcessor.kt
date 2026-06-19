package org.koitharu.kotatsu.parsers.ksp

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import java.io.File
import java.io.Writer
import java.util.*

class ParserProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private val availableLocales = Locale.getAvailableLocales().toSet()
    private val sourceNamePattern = Regex("[A-Z_][A-Z0-9_]{3,}")

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val kotatsuSymbols = resolver.getSymbolsWithAnnotation("org.koitharu.kotatsu.parsers.MangaSourceParser")
        val tachiyomiSymbols = resolver.getSymbolsWithAnnotation("org.koitharu.kotatsu.parsers.TachiyomiSource")

        val kotatsuRet = kotatsuSymbols.filterNot { it.validate() }.toList()
        val tachiyomiRet = tachiyomiSymbols.filterNot { it.validate() }.toList()

        if (!kotatsuSymbols.iterator().hasNext() && !tachiyomiSymbols.iterator().hasNext()) {
            return kotatsuRet + tachiyomiRet
        }
        val dependencies = Dependencies.ALL_FILES
        val factoryFile =
            try {
                codeGenerator.createNewFile(
                    dependencies = dependencies,
                    packageName = "org.koitharu.kotatsu.parsers",
                    fileName = "MangaParserFactory",
                )
            } catch (e: FileAlreadyExistsException) {
                logger.warn(e.toString(), null)
                null
            }
        val sourcesFile =
            try {
                codeGenerator.createNewFile(
                    dependencies = dependencies,
                    packageName = "org.koitharu.kotatsu.parsers.model",
                    fileName = "MangaSource",
                )
            } catch (e: FileAlreadyExistsException) {
                logger.warn(e.toString(), null)
                null
            }
        val totalCount = sourcesFile?.writer().use { sourcesWriter ->
            factoryFile?.writer().use { factoryWriter ->
                writeContent(sourcesWriter, factoryWriter, kotatsuSymbols, tachiyomiSymbols)
            }
        }
        writeSummary(totalCount)
        return kotatsuRet + tachiyomiRet
    }

    private fun writeContent(
        sourcesWriter: Writer?,
        factoryWriter: Writer?,
        kotatsuSymbols: Sequence<KSAnnotated>,
        tachiyomiSymbols: Sequence<KSAnnotated>,
    ): Int {
        if (sourcesWriter == null && factoryWriter == null) {
            return 0
        }
        factoryWriter?.write(
            """
			package org.koitharu.kotatsu.parsers

			import org.koitharu.kotatsu.parsers.model.MangaParserSource
			import org.koitharu.kotatsu.parsers.core.MangaParserWrapper
			import org.koitharu.kotatsu.parsers.compat.TachiyomiSourceAdapter

			internal fun MangaParserSource.newParser(context: MangaLoaderContext): MangaParser = when (this) {

			""".trimIndent(),
        )
        sourcesWriter?.write(
            """
			package org.koitharu.kotatsu.parsers.model

			public enum class MangaParserSource(
				override val title: String,
				override val locale: String,
				override val contentType: ContentType,
				override val isBroken: Boolean,
			): MangaSource {

			""".trimIndent(),
        )

        // Process native Kotatsu parsers
        val kotatsuVisitor = ParserVisitor(sourcesWriter, factoryWriter)
        val kotatsuCount = kotatsuSymbols
            .filter { it is KSClassDeclaration && it.validate() }
            .onEach { it.accept(kotatsuVisitor, Unit) }
            .count()

        // Process Tachiyomi source bridges
        val tachiyomiVisitor = TachiyomiVisitor(sourcesWriter, factoryWriter, kotatsuVisitor.titles)
        val tachiyomiCount = tachiyomiSymbols
            .filter { it is KSClassDeclaration && it.validate() }
            .onEach { it.accept(tachiyomiVisitor, Unit) }
            .count()

        factoryWriter?.write(
            """
			}.let {
				require(it.source == this) {
					"Cannot instantiate manga parser: ${'$'}name mapped to ${'$'}{it.source}"
				}
				MangaParserWrapper(it)
			}
			""".trimIndent(),
        )
        sourcesWriter?.write(
            """
				;
			}
			""".trimIndent(),
        )
        return kotatsuCount + tachiyomiCount
    }

    private fun writeSummary(totalCount: Int) {
        val file = File(options["summaryOutputDir"] ?: return, "summary.yaml")
        file.writeText("total: $totalCount")
    }

    private inner class ParserVisitor(
        private val sourcesWriter: Writer?,
        private val factoryWriter: Writer?,
    ) : KSVisitorVoid() {
        val titles = HashMap<String, String>()

        override fun visitClassDeclaration(
            classDeclaration: KSClassDeclaration,
            data: Unit,
        ) {
            if (classDeclaration.classKind != ClassKind.CLASS || classDeclaration.isAbstract()) {
                logger.error("Only non-abstract can be annotated with @MangaSourceParser", classDeclaration)
            }
            val annotation = classDeclaration.annotations.single { it.shortName.asString() == "MangaSourceParser" }
            val deprecation = classDeclaration.annotations.singleOrNull { it.shortName.asString() == "Deprecated" }
            val isBroken = classDeclaration.annotations.any { it.shortName.asString() == "Broken" }
            val name = annotation.arguments.single { it.name?.asString() == "name" }.value as String
            val title = annotation.arguments.single { it.name?.asString() == "title" }.value as String
            val locale = annotation.arguments.single { it.name?.asString() == "locale" }.value as String
            val type = annotation.arguments.single { it.name?.asString() == "type" }.value
            val localeString = "\"$locale\""
            val localeObj = if (locale.isEmpty()) null else Locale(locale)
            val localeTitle = localeObj?.getDisplayLanguage(localeObj)
            if (localeObj != null && localeObj !in availableLocales) {
                logger.error("Manga source $name has wrong locale: $localeTitle")
            }

            if (!sourceNamePattern.matches(name)) {
                logger.error("Manga source name must be uppercase: $name")
            }

            val constructor = classDeclaration.primaryConstructor
            if (constructor == null || constructor.parameters.count { !it.hasDefault } != 1) {
                logger.error(
                    "Class with @MangaSourceParser must have a primary constructor with one parameter",
                    classDeclaration,
                )
            }
            val className = checkNotNull(classDeclaration.qualifiedName?.asString()) { "Class name is null" }

            val prevTitleClass = titles.put(title, className)
            if (prevTitleClass != null) {
                logger.warn("Source title duplication: \"$title\" is assigned to both $prevTitleClass and $className")
            }

            factoryWriter?.write("\tMangaParserSource.$name -> $className(context)\n")
            val deprecationString =
                if (deprecation != null) {
                    val reason =
                        deprecation.arguments
                            .find { it.name?.asString() == "message" }
                            ?.value
                            ?.toString() ?: "Unknown reason"
                    "@Deprecated(\"$reason\") "
                } else {
                    ""
                }
            val localeComment = localeTitle?.toTitleCase(localeObj)?.let { " /* $it */" }.orEmpty()
            sourcesWriter?.write(
                "\t$deprecationString$name(\"$title\", $localeString$localeComment, $type, $isBroken),\n",
            )
        }
    }

    /**
     * Visitor for @TachiyomiSource annotations.
     * Generates factory entries that instantiate the Tachiyomi HttpSource and wrap it with TachiyomiSourceAdapter.
     */
    private inner class TachiyomiVisitor(
        private val sourcesWriter: Writer?,
        private val factoryWriter: Writer?,
        private val titles: HashMap<String, String>,
    ) : KSVisitorVoid() {

        override fun visitClassDeclaration(
            classDeclaration: KSClassDeclaration,
            data: Unit,
        ) {
            val annotation = classDeclaration.annotations.single { it.shortName.asString() == "TachiyomiSource" }

            // Get annotation arguments with defaults
            val nameArg = annotation.arguments.find { it.name?.asString() == "name" }?.value as? String ?: ""
            val titleArg = annotation.arguments.find { it.name?.asString() == "title" }?.value as? String ?: ""
            val localeArg = annotation.arguments.find { it.name?.asString() == "locale" }?.value as? String ?: ""
            val typeArg = annotation.arguments.find { it.name?.asString() == "type" }?.value?.toString()
            val type = if (typeArg.isNullOrBlank() || typeArg == "null") "ContentType.MANGA" else typeArg

            // Auto-detect values from class if not provided
            val className = classDeclaration.simpleName.asString()
            val qualifiedClassName = checkNotNull(classDeclaration.qualifiedName?.asString()) {
                "Class qualified name is null"
            }

            // Generate source name from class name (UPPER_CASE)
            val sourceName = nameArg.takeIf { it.isNotBlank() }
                ?: className.uppercase().replace(Regex("[^A-Z0-9_]"), "_").let {
                    // Ensure it starts with a letter
                    if (it.firstOrNull()?.isLetter() == true) it else "SOURCE_$it"
                }

            // Use provided title or class name
            val sourceTitle = titleArg.takeIf { it.isNotBlank() } ?: className

            // Auto-detect locale from package name if not provided
            val locale = localeArg.takeIf { it.isNotBlank() }
                ?: detectLocaleFromPackage(qualifiedClassName)

            val localeString = "\"$locale\""
            val localeObj = if (locale.isEmpty()) null else Locale(locale)
            val localeTitle = localeObj?.getDisplayLanguage(localeObj)

            if (localeObj != null && localeObj !in availableLocales) {
                logger.error("Tachiyomi source $sourceName has wrong locale: $localeTitle")
            }

            if (!sourceNamePattern.matches(sourceName)) {
                logger.error("Tachiyomi source name must be uppercase: $sourceName")
            }

            val prevTitleClass = titles.put(sourceTitle, qualifiedClassName)
            if (prevTitleClass != null) {
                logger.warn("Source title duplication: \"$sourceTitle\" is assigned to both $prevTitleClass and $qualifiedClassName")
            }

            // Factory: wrap the Tachiyomi source with TachiyomiSourceAdapter
            factoryWriter?.write(
                "\tMangaParserSource.$sourceName -> TachiyomiSourceAdapter(context, MangaParserSource.$sourceName, $qualifiedClassName())\n"
            )

            // Enum entry
            val localeComment = localeTitle?.toTitleCase(localeObj)?.let { " /* $it */" }.orEmpty()
            sourcesWriter?.write(
                "\t$sourceName(\"$sourceTitle\", $localeString$localeComment, $type, false),\n",
            )
        }

        private fun detectLocaleFromPackage(qualifiedName: String): String {
            // Common locale patterns in package names
            val localePatterns = mapOf(
                ".vi." to "vi", ".en." to "en", ".ja." to "ja", ".ko." to "ko",
                ".zh." to "zh", ".es." to "es", ".fr." to "fr", ".de." to "de",
                ".it." to "it", ".pt." to "pt", ".ru." to "ru", ".ar." to "ar",
                ".th." to "th", ".id." to "id", ".tr." to "tr", ".pl." to "pl",
                ".all." to "", ".multi." to ""
            )

            return localePatterns.entries.find { qualifiedName.contains(it.key) }?.value
                ?: ""
        }
    }
}
