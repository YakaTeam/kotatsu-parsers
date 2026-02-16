package org.koitharu.kotatsu.parsers.site.pizzareader.fr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.pizzareader.PizzaReaderParser
import java.util.EnumSet

@MangaSourceParser("BLUESOLO", "BlueSolo", "fr")
internal class BlueSolo(context: MangaLoaderContext) :
	PizzaReaderParser(context, MangaParserSource.BLUESOLO, "bluesolo.org") {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
	)

	override val ongoingFilter = "en cours"
	override val completedFilter = "terminé"
	override val hiatusFilter = "hiatus"
	override val abandonedFilter = "cancel"
}
