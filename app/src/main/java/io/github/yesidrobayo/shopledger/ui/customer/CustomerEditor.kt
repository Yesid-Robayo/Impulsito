package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La hoja de un cliente mientras se escribe.
 *
 * [editingId] nulo es un cliente nuevo. [priceListId] es su lista de precios, o `null` para la
 * inicial; [priceLists] son las vigentes que se ofrecen, solo si hay más de una. Sin ellas, la hoja no
 * la muestra y se conserva la que tenía para no perderla al editar. [issues] son los problemas del
 * último intento de guardar, que se borran del campo que la persona vuelve a tocar.
 * [sameNameWarned] dice que ya se avisó de un nombre repetido: el siguiente `Guardar` crea igual,
 * porque dos personas pueden llamarse igual. [noContactsApp] avisa que no hay app de contactos, sin
 * cerrar el formulario. Lo escrito siempre se conserva.
 */
@Immutable
data class CustomerEditor(
    val editingId: Long?,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val priceListId: Long? = null,
    val priceLists: List<PriceList> = emptyList(),
    val issues: Set<CustomerFormIssue> = emptySet(),
    val sameNameWarned: Boolean = false,
    val noContactsApp: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
) {
    /** Lo escrito, como lo valida el dominio. */
    val form: CustomerForm get() = CustomerForm(name, phone, email, notes, priceListId)
}

/** Lo que la persona hace en la hoja de un cliente. */
sealed interface CustomerEditorAction {
    data class Name(val text: String) : CustomerEditorAction

    data class Phone(val text: String) : CustomerEditorAction

    data class Email(val text: String) : CustomerEditorAction

    data class Notes(val text: String) : CustomerEditorAction

    /** Elige la lista de precios del cliente; la inicial lo deja sin lista propia. */
    data class ChoosePriceList(val listId: Long) : CustomerEditorAction

    /** Abre el selector de contactos del sistema: lo resuelve la navegación. */
    data object PickContact : CustomerEditorAction

    data object Save : CustomerEditorAction

    data object Dismiss : CustomerEditorAction
}

/**
 * La hoja de crear o editar un cliente, para la lista y para el detalle.
 *
 * Los errores aparecen al guardar, nunca mientras se escribe, y lo escrito no se pierde por un error.
 * Un nombre repetido no es un error: se avisa una vez y se puede guardar igual. Guardar corre una
 * sola vez a la vez.
 *
 * @param onSaved recibe el id del cliente guardado, con la hoja ya cerrada.
 * @param priceLists las listas de precios vigentes en este momento.
 */
class CustomerEditorModel(
    private val scope: CoroutineScope,
    private val save: suspend (Long?, CustomerForm, Boolean) -> SaveCustomerResult,
    private val onSaved: (Long) -> Unit,
    private val priceLists: () -> List<PriceList> = { emptyList() },
) {
    /** Las listas que se ofrecen: con una sola no hay nada que elegir. */
    private fun offered(): List<PriceList> = priceLists().takeIf { it.size > 1 }.orEmpty()

    private val state = MutableStateFlow<CustomerEditor?>(null)
    val editor: StateFlow<CustomerEditor?> = state.asStateFlow()

    /** Abre la hoja vacía, con [name] ya escrito si viene de una búsqueda sin resultados. */
    fun openNew(name: String = "") {
        state.value = CustomerEditor(editingId = null, name = name, priceLists = offered())
    }

    /**
     * Abre la hoja con los datos de [customer] para editarlos. Un cliente anonimizado ya no tiene
     * datos que editar: no abre nada.
     */
    fun openEdit(customer: Customer) {
        val details = customer.details ?: return
        val contact = details.contact
        state.value =
            CustomerEditor(
                editingId = customer.id,
                name = contact.name,
                phone = contact.phone.orEmpty(),
                email = contact.email.orEmpty(),
                notes = contact.notes.orEmpty(),
                priceListId = details.priceListId,
                priceLists = offered(),
            )
    }

    /**
     * Llena la hoja con lo que trajo el selector de contactos: solo el nombre y el teléfono, y solo
     * lo que el contacto tenga. Un contacto sin teléfono deja el que ya estaba escrito. Todo se puede
     * editar antes de guardar.
     */
    fun fillFromContact(name: String?, phone: String?) {
        change {
            copy(
                name = name?.takeIf { it.isNotBlank() } ?: this.name,
                phone = phone?.takeIf { it.isNotBlank() } ?: this.phone,
                issues = issues - NAME_ISSUES - PHONE_ISSUES,
                sameNameWarned = false,
                noContactsApp = false,
                saveFailed = null,
            )
        }
    }

    /** Avisa que no hay una app de contactos. El formulario sigue como estaba. */
    fun contactsUnavailable() {
        change { copy(noContactsApp = true) }
    }

    fun onAction(action: CustomerEditorAction) {
        when (action) {
            is CustomerEditorAction.Name ->
                change {
                    copy(
                        name = action.text,
                        issues = issues - NAME_ISSUES,
                        sameNameWarned = false,
                        saveFailed = null,
                    )
                }

            is CustomerEditorAction.Phone ->
                change {
                    copy(phone = action.text, issues = issues - PHONE_ISSUES, saveFailed = null)
                }

            is CustomerEditorAction.Email ->
                change {
                    copy(email = action.text, issues = issues - EMAIL_ISSUES, saveFailed = null)
                }

            is CustomerEditorAction.Notes ->
                change {
                    copy(
                        notes = action.text,
                        issues = issues - CustomerFormIssue.NOTES_TOO_LONG,
                        saveFailed = null,
                    )
                }

            is CustomerEditorAction.ChoosePriceList ->
                change {
                    val chosen = priceLists.firstOrNull { it.id == action.listId }
                    copy(
                        priceListId = chosen?.id?.takeUnless {
                            chosen.isDefault
                        },
                        saveFailed = null,
                    )
                }

            // Lo resuelve la navegación, que tiene el selector del sistema.
            CustomerEditorAction.PickContact -> change { copy(noContactsApp = false) }

            CustomerEditorAction.Save -> save()

            CustomerEditorAction.Dismiss -> state.value = null
        }
    }

    private fun change(block: CustomerEditor.() -> CustomerEditor) {
        state.update { it?.block() }
    }

    private fun save() {
        val current = state.value ?: return
        if (current.saving) return
        change { copy(saving = true, saveFailed = null) }
        scope.launch {
            when (val result = save(current.editingId, current.form, current.sameNameWarned)) {
                is SaveCustomerResult.Saved -> {
                    state.value = null
                    onSaved(result.customerId)
                }

                is SaveCustomerResult.Invalid ->
                    change { copy(saving = false, issues = result.issues) }

                SaveCustomerResult.SameName ->
                    change { copy(saving = false, sameNameWarned = true) }

                // Se eliminó o se anonimizó mientras se editaba: no se guardó nada, y lo escrito
                // sigue a la vista.
                SaveCustomerResult.NotFound, SaveCustomerResult.Anonymized ->
                    change { copy(saving = false, saveFailed = SaveFailure(outOfSpace = false)) }

                is SaveCustomerResult.StorageFailed ->
                    change {
                        copy(saving = false, saveFailed = SaveFailure(result.failure.isOutOfSpace))
                    }
            }
        }
    }

    private companion object {
        val NAME_ISSUES = setOf(CustomerFormIssue.NAME_EMPTY, CustomerFormIssue.NAME_TOO_LONG)
        val PHONE_ISSUES = setOf(CustomerFormIssue.PHONE_TOO_LONG, CustomerFormIssue.PHONE_FORMAT)
        val EMAIL_ISSUES = setOf(CustomerFormIssue.EMAIL_TOO_LONG, CustomerFormIssue.EMAIL_FORMAT)
    }
}
