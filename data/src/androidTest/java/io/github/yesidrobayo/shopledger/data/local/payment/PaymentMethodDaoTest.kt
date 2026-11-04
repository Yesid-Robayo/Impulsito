package io.github.yesidrobayo.shopledger.data.local.payment

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.mediaAsset
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los métodos de pago: la base nace con los seis iniciales, sin nombres ni marcas, cada columna
 * vuelve como se guardó, la clave es única y el nombre único entre los vigentes, la imagen se
 * suelta al borrarla, las listas van por `sort_order` y después por `id`, con archivo y
 * reactivación, y un método con pagos no se borra.
 */
@RunWith(AndroidJUnit4::class)
internal class PaymentMethodDaoTest : LedgerDatabaseTest() {
    private val methods get() = database.paymentMethodDao()

    @Test
    fun aNewDatabaseHasTheSixGenericMethodsWithoutNamesOrBrands() = runBlocking {
        val seeded = methods.observeActive().first()

        // Uno por tipo, con el efectivo primero, y su clave es el nombre del tipo.
        assertEquals(INITIAL_KEYS, seeded.map { it.systemKey })
        assertEquals(INITIAL_KEYS, seeded.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), seeded.map { it.sortOrder })
        for (method in seeded) {
            // Ningún texto visible: el nombre sale traducido de la clave.
            val expected =
                PaymentMethodEntity(
                    id = method.id,
                    systemKey = method.kind,
                    name = null,
                    normalizedName = null,
                    kind = method.kind,
                    feeBp = 0,
                    feeFixed = 0,
                    settlementDays = 0,
                    priceAdjustmentBp = 0,
                    moneyAccountId = null,
                    instructionsText = null,
                    instructionsMediaId = null,
                    sortOrder = method.sortOrder,
                    archivedAt = null,
                    createdAt = CREATED_AT,
                    updatedAt = CREATED_AT,
                )
            assertEquals(expected, method)
        }
        assertEquals(6L, count("SELECT COUNT(*) FROM payment_methods"))
        assertEquals(seeded.first(), methods.findBySystemKey("CASH"))
        assertNull(methods.findBySystemKey("BANK"))
    }

    @Test
    fun aMethodOfThePersonReadsBackWithEveryColumn() = runBlocking {
        val qr = database.mediaAssetDao().insert(mediaAsset('b').copy(kind = "PAYMENT_QR"))
        val bank = database.moneyAccountDao().insert(moneyAccount("Banco"))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val method =
            paymentMethod("Billetera del barrio", kind = "WALLET", sortOrder = 7).copy(
                feeBp = 299,
                feeFixed = 15,
                settlementDays = 2,
                priceAdjustmentBp = -500,
                moneyAccountId = bank,
                instructionsText = "Enviar al número de la tienda",
                instructionsMediaId = qr,
                updatedAt = LATER,
            )

        val id = methods.insert(method)

        assertEquals(method.copy(id = id), methods.findById(id))
        assertEquals(id, methods.findActiveByNormalizedName("billetera del barrio")?.id)
        assertNull(methods.findById(MISSING_ID))
        assertNull(methods.findActiveByNormalizedName("otra"))
    }

    @Test
    fun keysCannotRepeatAndNamesAreUniqueOnlyAmongActiveMethods() = runBlocking {
        constraintFailure(UNIQUE) {
            val unnamed = paymentMethod("Efectivo").copy(name = null, normalizedName = null)
            methods.insert(unnamed.copy(systemKey = "CASH"))
        }
        val id = methods.insert(paymentMethod("Tarjeta Débito", kind = "CARD"))
        // Tildes, mayúsculas y espacios de más no hacen otro nombre: la guarda lo rechaza.
        guardFailure(GUARD) { methods.insert(paymentMethod("  TARJETA   DEBITO ")) }

        // Un archivado no reserva su nombre: la unicidad es solo entre los vigentes.
        methods.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER)
        assertNull(methods.findActiveByNormalizedName("tarjeta debito"))
        val again = methods.insert(paymentMethod("Tarjeta débito", kind = "CARD"))
        assertEquals(again, methods.findActiveByNormalizedName("tarjeta debito")?.id)

        // Con un vigente de ese nombre, el archivado no se reactiva, y otro no se renombra a él.
        guardFailure(GUARD) {
            methods.setArchivedAt(id, archivedAt = null, updatedAt = MUCH_LATER)
        }
        val other = methods.insert(paymentMethod("Vale", kind = "OTHER"))
        guardFailure(GUARD) {
            methods.update(
                other,
                "Tarjeta débito",
                "tarjeta debito",
                "OTHER",
                feeBp = 0,
                feeFixed = 0,
                settlementDays = 0,
                priceAdjustmentBp = 0,
                moneyAccountId = null,
                instructionsText = null,
                instructionsMediaId = null,
                updatedAt = MUCH_LATER,
            )
        }
        assertEquals("vale", methods.findById(other)?.normalizedName)
        assertEquals(LATER, methods.findById(id)?.archivedAt)
        assertEquals(9L, count("SELECT COUNT(*) FROM payment_methods"))
    }

    @Test
    fun deletingTheInstructionsImageLeavesTheMethodWithoutIt() = runBlocking {
        val media = database.mediaAssetDao()
        val qr = media.insert(mediaAsset('c').copy(kind = "PAYMENT_QR"))
        val id = methods.insert(paymentMethod("Billetera").copy(instructionsMediaId = qr))
        constraintFailure(FOREIGN_KEY) {
            methods.insert(paymentMethod("Huérfano").copy(instructionsMediaId = MISSING_ID))
        }

        assertEquals(1, media.delete(qr))

        assertNull(methods.findById(id)?.instructionsMediaId)
        assertEquals("billetera", methods.findById(id)?.normalizedName)
    }

    @Test
    fun theKindIsOneTheAppKnowsAndANamelessMethodNeedsAKey() = runBlocking {
        guardFailure(GUARD) { methods.insert(paymentMethod("Cheque", kind = "CHECK")) }
        guardFailure(GUARD) { methods.insert(paymentMethod("Sin nombre").copy(name = null)) }
        guardFailure(GUARD) {
            methods.insert(paymentMethod("Nada").copy(name = null, normalizedName = null))
        }
        guardFailure(GUARD) { methods.insert(paymentMethod("Cara").copy(feeBp = 10_001)) }
        guardFailure(GUARD) {
            methods.insert(paymentMethod("Recargo").copy(priceAdjustmentBp = 10_001))
        }
        val discount = methods.insert(paymentMethod("Descuento").copy(priceAdjustmentBp = -10_000))

        assertEquals(-10_000L, methods.findById(discount)?.priceAdjustmentBp)
        assertEquals(7L, count("SELECT COUNT(*) FROM payment_methods"))
    }

    @Test
    fun aRestoredMethodWithAnUnknownKindCanBeTurnedIntoOther() = runBlocking {
        // Una copia de una app más nueva puede traer un tipo que esta no conoce. Se simula sin la
        // guarda de inserción, que la restauración no vuelve a correr sobre filas ya guardadas.
        val insertGuard = SchemaGuards.named("guard_payment_methods_insert")
        execute("DROP TRIGGER ${insertGuard.name}")
        val id = methods.insert(paymentMethod("Cripto", kind = "CRYPTO"))
        execute(insertGuard.sql)

        // Editarlo con su tipo desconocido no pasa, y llevarlo a otro medio sí.
        guardFailure(GUARD) { methods.updateSortOrder(id, sortOrder = 1, updatedAt = LATER) }
        val changed =
            methods.update(
                id,
                "Cripto",
                "cripto",
                PaymentMethodKind.OTHER.name,
                feeBp = 0,
                feeFixed = 0,
                settlementDays = 0,
                priceAdjustmentBp = 0,
                moneyAccountId = null,
                instructionsText = null,
                instructionsMediaId = null,
                updatedAt = LATER,
            )

        assertEquals(1, changed)
        assertEquals("OTHER", methods.findById(id)?.kind)
        guardFailure(GUARD) { methods.insert(paymentMethod("Otra cripto", kind = "CRYPTO")) }
    }

    @Test
    fun listsFollowSortOrderThenCreationAndEmitAgainOnArchive() = runBlocking {
        val seeded = methods.observeActive().first().map { it.id }
        val late = methods.insert(paymentMethod("Vale de regalo", sortOrder = 20))
        val tie = methods.insert(paymentMethod("Bono", sortOrder = 20))
        val early = methods.insert(paymentMethod("Fiado del barrio", sortOrder = 0))

        val active = Emissions(this, methods.observeActive().ids { it.id })
        val archived = Emissions(this, methods.observeArchived().ids { it.id })
        try {
            // Por el orden de la persona, y con el mismo orden, por creación.
            active.awaitValue(listOf(early) + seeded + listOf(late, tie))
            archived.awaitValue(emptyList())

            methods.setArchivedAt(late, archivedAt = LATER, updatedAt = LATER)
            assertEquals(1, methods.updateSortOrder(tie, sortOrder = 3, updatedAt = LATER))
            active.awaitValue(listOf(early) + seeded.take(3) + listOf(tie) + seeded.drop(3))
            archived.awaitValue(listOf(late))

            methods.setArchivedAt(late, archivedAt = null, updatedAt = MUCH_LATER)
            active.awaitValue(listOf(early) + seeded.take(3) + listOf(tie) + seeded.drop(3) + late)
            archived.awaitValue(emptyList())
        } finally {
            active.stop()
            archived.stop()
        }
        assertEquals(MUCH_LATER, methods.findById(late)?.updatedAt)
        assertEquals(0, methods.updateSortOrder(MISSING_ID, sortOrder = 1, updatedAt = LATER))
        assertEquals(0, methods.setArchivedAt(MISSING_ID, archivedAt = LATER, updatedAt = LATER))
    }

    @Test
    fun updateChangesWhatThePersonWritesAndKeepsTheRest() = runBlocking {
        val cash = requireNotNull(methods.findBySystemKey("CASH"))
        methods.setArchivedAt(cash.id, archivedAt = LATER, updatedAt = LATER)
        val box = database.moneyAccountDao().insert(moneyAccount("Caja chica", kind = "CASH"))

        // La persona renombra un inicial: desde ahí se muestra su nombre, y la clave se queda.
        val changed =
            methods.update(
                cash.id,
                "Caja",
                "caja",
                "CASH",
                feeBp = 0,
                feeFixed = 0,
                settlementDays = 0,
                priceAdjustmentBp = 250,
                moneyAccountId = box,
                instructionsText = "Contar dos veces",
                instructionsMediaId = null,
                updatedAt = MUCH_LATER,
            )

        assertEquals(1, changed)
        val expected =
            cash.copy(
                name = "Caja",
                normalizedName = "caja",
                priceAdjustmentBp = 250,
                moneyAccountId = box,
                instructionsText = "Contar dos veces",
                archivedAt = LATER,
                updatedAt = MUCH_LATER,
            )
        assertEquals(expected, methods.findById(cash.id))
        assertEquals(
            0,
            methods.update(
                MISSING_ID,
                "Nada",
                "nada",
                "OTHER",
                feeBp = 0,
                feeFixed = 0,
                settlementDays = 0,
                priceAdjustmentBp = 0,
                moneyAccountId = null,
                instructionsText = null,
                instructionsMediaId = null,
                updatedAt = LATER,
            ),
        )
    }

    @Test
    fun aMethodWithPaymentsIsNotDeletedEvenIfItsSaleIsVoided() = runBlocking {
        val unused = methods.insert(paymentMethod("Sin uso"))
        val card = requireNotNull(methods.findBySystemKey("CARD")).id
        val saleId = database.saleDao().insert(sale(priceListId))
        database.salePaymentDao().insert(cardPayment(saleId, card).copy(amount = 12_800))
        database.saleDao().markVoided(saleId, LATER, "ENTRY_ERROR", null)

        assertTrue(methods.hasSalePayments(card))
        assertFalse(methods.hasSalePayments(unused))
        constraintFailure(FOREIGN_KEY) { methods.delete(card) }

        assertEquals(1, methods.delete(unused))
        assertNull(methods.findById(unused))
        assertEquals(card, methods.findById(card)?.id)
        assertEquals(0, methods.delete(MISSING_ID))
    }

    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "index_payment_methods_instructions_media_id (instructions_media_id)",
                "index_payment_methods_money_account_id (money_account_id)",
                "index_payment_methods_normalized_name (normalized_name)",
                "unique index_payment_methods_system_key (system_key)",
            ),
            database.indexesOf("payment_methods"),
        )
    }

    private companion object {
        const val GUARD = "guard:payment_methods"
        const val MISSING_ID = 999L
        val INITIAL_KEYS = listOf("CASH", "CARD", "TRANSFER", "WALLET", "ON_ACCOUNT", "OTHER")
    }
}
