package io.github.yesidrobayo.shopledger.ui.payment

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ArchivePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.CreatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.FindPaymentImagesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ImportPaymentImageUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.LoadPaymentMethodOptionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveCashRoundingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObservePaymentMethodsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReactivatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReorderPaymentMethodsUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

// Apoyo de las pruebas de las pantallas de métodos de pago. Nombres y comisiones inventados, sin
// marcas.

internal val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

/** Símbolos con coma decimal y punto de grupos, como escribe muchas regiones. */
internal val COMMA = NumberSymbols(',', '-', DigitGrouping('.', 3, 3))

internal val NUMBERS = FormNumbers(COMMA, COMMA, currencyDecimals = 2)

/**
 * Una base de mentira: guarda los métodos en memoria y confirma cada escritura al instante. Sin
 * transacciones de verdad, que ya prueba el dominio: aquí se prueba lo que hace la pantalla.
 */
internal class FakePaymentMethods :
    PaymentMethodRepository,
    MediaAssetRepository,
    TransactionRunner {
    val images = mutableMapOf<Long, StoredImage>()
    val orphaned = mutableSetOf<Long>()

    /** Lo que devuelve el importador de mentira al elegir una imagen. */
    var nextImport: ImageImport = ImageImport.Unreadable

    val methods = MutableStateFlow<Map<Long, PaymentMethodRecord>>(emptyMap())
    val withSales = mutableSetOf<Long>()
    var failure: StorageFailure? = null
    private var nextId = 1L

    fun seedInitial() {
        listOf("CASH", "CARD", "TRANSFER", "WALLET", "ON_ACCOUNT", "OTHER").forEachIndexed {
                i,
                key,
            ->
            put(
                PaymentMethodRecord(
                    nextId++,
                    key,
                    details(null, PaymentMethodKind.valueOf(key)),
                    i + 1L,
                    null,
                    AT,
                    AT,
                ),
            )
        }
    }

    fun put(method: PaymentMethodRecord) {
        methods.value = methods.value + (method.id to method)
    }

    override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
        failure?.let { throw StorageFailureException(it) }
        val before = methods.value
        return when (val result = block()) {
            is TxResult.Commit -> result.value

            is TxResult.CommitUncounted -> result.value

            is TxResult.Rollback -> {
                methods.value = before
                result.value
            }
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T = block()

    override fun observe(filter: ArchiveFilter): Flow<List<PaymentMethodRecord>> =
        methods.map { all -> ordered(all.values, filter) }

    override suspend fun findActive(): List<PaymentMethodRecord> =
        ordered(methods.value.values, ArchiveFilter.ACTIVE)

    override suspend fun findById(id: Long): PaymentMethodRecord? = methods.value[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): PaymentMethodRecord? =
        findActive().firstOrNull { it.details.normalizedName == normalizedName }

    override suspend fun hasSalePayments(id: Long): Boolean = id in withSales

    override suspend fun isActiveMoneyAccount(id: Long): Boolean = false

    override suspend fun maxSortOrder(): Long? = methods.value.values.maxOfOrNull { it.sortOrder }

    override suspend fun insert(
        details: PaymentMethodDetails,
        sortOrder: Long,
        createdAt: Instant,
    ): Long {
        val id = nextId++
        put(PaymentMethodRecord(id, null, details, sortOrder, null, createdAt, createdAt))
        return id
    }

    override suspend fun update(id: Long, details: PaymentMethodDetails, updatedAt: Instant) {
        put(requireNotNull(methods.value[id]).copy(details = details, updatedAt = updatedAt))
    }

    override suspend fun setSortOrder(id: Long, sortOrder: Long, updatedAt: Instant) {
        put(requireNotNull(methods.value[id]).copy(sortOrder = sortOrder))
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        put(requireNotNull(methods.value[id]).copy(archivedAt = archivedAt))
    }

    override suspend fun delete(id: Long) {
        methods.value = methods.value - id
    }

    override suspend fun findIdBySha256(sha256: String): Long? =
        images.entries.firstOrNull { it.value.sha256 == sha256 }?.key

    override suspend fun findImage(id: Long): StoredImage? = images[id]

    override suspend fun insert(image: StoredImage, kind: MediaKind, createdAt: Instant): Long {
        val id = IMAGE_IDS + images.size
        images[id] = image
        return id
    }

    override suspend fun isReferenced(id: Long): Boolean =
        methods.value.values.any { it.details.instructionsMediaId == id }

    override suspend fun setOrphanedAt(id: Long, orphanedAt: Instant?) {
        if (orphanedAt == null) orphaned -= id else orphaned += id
    }

    private fun ordered(all: Collection<PaymentMethodRecord>, filter: ArchiveFilter) = all
        .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
        .sortedWith(compareBy<PaymentMethodRecord> { it.sortOrder }.thenBy { it.id })

    fun formUseCases(clock: Clock = Clock { AT }) = PaymentMethodFormUseCases(
        observe = ObservePaymentMethodsUseCase(this),
        loadOptions = LoadPaymentMethodOptionsUseCase(this, this, this),
        create = CreatePaymentMethodUseCase(this, this, this, clock),
        edit = EditPaymentMethodUseCase(this, this, this, clock),
        archive = ArchivePaymentMethodUseCase(this, this, clock),
        reactivate = ReactivatePaymentMethodUseCase(this, this, clock),
        delete = DeletePaymentMethodUseCase(this, this, this, clock),
        observeCashRounding = ObserveCashRoundingUseCase(NoSettings),
        importImage = ImportPaymentImageUseCase { _, _ -> nextImport },
    )

    fun listViewModel() = PaymentMethodsViewModel(
        ObservePaymentMethodsUseCase(this),
        ReorderPaymentMethodsUseCase(this, this, Clock { AT }),
        FindPaymentImagesUseCase(this, this),
    )

    companion object {
        private const val IMAGE_IDS = 100L

        fun details(
            name: String?,
            kind: PaymentMethodKind,
            fee: Long = 0,
            fixed: Long = 0,
            days: Int = 0,
        ) = PaymentMethodDetails(
            name = name,
            normalizedName = name?.lowercase(),
            kind = kind,
            feeRate = BasisPoints(fee),
            feeFixed = Money(fixed),
            settlementDays = days,
            priceAdjustment = BasisPoints.ZERO,
            moneyAccountId = null,
            instructionsText = null,
            instructionsMediaId = null,
        )
    }
}

/** Unos ajustes que nunca emiten: el redondeo de efectivo se queda en cero, como sin ajustes. */
internal object NoSettings : AppSettingsRepository {
    override fun observe(): Flow<AppSettings> = emptyFlow()

    override suspend fun get(): AppSettings = error("Sin ajustes en esta prueba")

    override suspend fun updateProfile(profile: BusinessProfile, updatedAt: Instant) =
        error("Solo lectura")

    override suspend fun lockCurrency(lockedAt: Instant): Boolean = error("Solo lectura")

    override suspend fun updateLoyalty(loyalty: LoyaltySettings, updatedAt: Instant) =
        error("Solo lectura")

    override suspend fun updateTipsEnabled(enabled: Boolean, updatedAt: Instant) =
        error("Solo lectura")

    override suspend fun updatePreference(change: PreferenceChange, updatedAt: Instant) =
        error("Solo lectura")

    override suspend fun markOnboardingCompleted(completedAt: Instant) = error("Solo lectura")

    override suspend fun updateRegional(input: RegionalSettingsInput, updatedAt: Instant) =
        error("Solo lectura")
}
