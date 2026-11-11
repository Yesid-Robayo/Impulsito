package io.github.yesidrobayo.shopledger.data.destination.slots

/**
 * Una nube del teléfono aprobada para las ranuras.
 *
 * @property authority la autoridad de los documentos de esa nube: dice qué app los guarda.
 * @property label su nombre para la persona. Alimenta `{provider}`, y nunca sale del proveedor.
 */
internal data class SlotProvider(val authority: String, val label: String)

/**
 * La lista fija de nubes en que la app prepara las ranuras. Hoy solo trae Drive.
 *
 * Es fija porque una app normal no puede leer las raíces de un proveedor de documentos: no tiene
 * cómo distinguir una nube de una carpeta del teléfono. Un documento de otra autoridad se rechaza,
 * sea otra nube o una carpeta local. Sumar una nube exige probar con ella que el permiso dura, que
 * la escritura en segundo plano sube y que informa el tamaño, y una decisión escrita.
 */
internal val SLOT_PROVIDERS: List<SlotProvider> =
    listOf(SlotProvider(authority = "com.google.android.apps.docs.storage", label = "Drive"))

/** El `destination_id` de las ranuras: no hay cuenta ni dirección que las distinga. */
internal const val DOCUMENT_SLOTS_DESTINATION_ID = "document-slots"

/** La nube aprobada de [authority] en [providers], o nula si no está en la lista. */
internal fun slotProviderOf(
    authority: String?,
    providers: List<SlotProvider> = SLOT_PROVIDERS,
): SlotProvider? = providers.firstOrNull { it.authority == authority }
