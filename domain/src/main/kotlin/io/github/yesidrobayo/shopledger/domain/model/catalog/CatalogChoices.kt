package io.github.yesidrobayo.shopledger.domain.model.catalog

/** Lo que el formulario de un producto ofrece elegir: las categorías y las unidades vigentes. */
data class CatalogChoices(val categories: List<Category>, val units: List<MeasureUnit>)
