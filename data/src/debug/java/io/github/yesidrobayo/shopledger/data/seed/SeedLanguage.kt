package io.github.yesidrobayo.shopledger.data.seed

/**
 * El idioma de los nombres inventados del negocio de prueba: productos, categorías, proveedores,
 * clientes y alguna nota.
 *
 * Son datos de prueba, no textos de la interfaz: por eso viven aquí y no en los recursos
 * traducibles, y se eligen por parámetro, nunca por el idioma del teléfono. Todos son inventados,
 * sin marcas ni personas reales, y llevan a propósito tildes, `ñ`, `ç` y `ã`, que la búsqueda y el
 * orden deben manejar.
 */
enum class SeedLanguage {
    SPANISH,
    ENGLISH,
    PORTUGUESE,
    ;

    internal val vocabulary: SeedVocabulary
        get() = when (this) {
            SPANISH -> SeedVocabulary.SPANISH
            ENGLISH -> SeedVocabulary.ENGLISH
            PORTUGUESE -> SeedVocabulary.PORTUGUESE
        }
}

/**
 * Las palabras de un idioma. Un nombre de producto junta un sustantivo, un calificativo y una
 * variedad, así que hay 6.000 nombres distintos, más que los 5.000 productos del volumen mayor.
 * Los clientes juntan un nombre y un apellido, y los proveedores un prefijo y un nombre.
 */
internal class SeedVocabulary(
    val productNouns: List<String>,
    val productQualifiers: List<String>,
    val productVarieties: List<String>,
    val longDescription: String,
    val categories: List<String>,
    val supplierPrefixes: List<String>,
    val supplierNames: List<String>,
    val firstNames: List<String>,
    val lastNames: List<String>,
    val secondLocation: String,
    val bankAccount: String,
    val paymentMethods: Map<String, String>,
    val expenseNotes: List<String>,
) {
    companion object {
        val SPANISH =
            SeedVocabulary(
                productNouns =
                    listOf(
                        "Café", "Azúcar", "Piña", "Jabón", "Té", "Galletas", "Arroz", "Fríjoles",
                        "Harina", "Aceite", "Mantequilla", "Queso", "Pan", "Leche", "Chocolate",
                        "Avena", "Lentejas", "Atún", "Champú", "Cepillo", "Vela", "Fósforos",
                        "Detergente", "Servilletas", "Salsa", "Mermelada", "Miel", "Maní", "Ñame",
                        "Ají",
                    ),
                productQualifiers =
                    listOf(
                        "dulce", "integral", "suave", "picante", "clásico", "tostado", "natural",
                        "casero", "fino", "extra", "añejo", "familiar", "económico", "artesanal",
                        "cremoso", "crujiente", "molido", "campesino", "ahumado", "tradicional",
                    ),
                productVarieties =
                    listOf(
                        "250 g", "500 g", "1 kg", "x6", "x12", "pequeño", "mediano", "grande",
                        "de temporada", "surtido",
                    ),
                longDescription =
                    "edición especial para compartir en familia con empaque reciclable y el " +
                        "sabor de siempre",
                categories =
                    listOf(
                        "Abarrotes", "Bebidas", "Lácteos", "Panadería", "Limpieza",
                        "Aseo personal", "Dulces", "Pasabocas", "Frutas", "Verduras", "Carnes",
                        "Congelados", "Granos", "Enlatados", "Papelería", "Hogar", "Mascotas",
                        "Bebés", "Ferretería", "Cafetería",
                    ),
                supplierPrefixes =
                    listOf(
                        "Distribuidora", "Comercial", "Granja", "Almacén", "Bodega", "Importadora",
                        "Panificadora", "Lácteos", "Productos", "Surtidora",
                    ),
                supplierNames =
                    listOf(
                        "Arroyo Verde", "Colina Azul", "El Mirador", "La Cañada", "Piedra Blanca",
                        "Monte Sereno", "Río Manso", "Las Acacias", "El Portal", "La Ribera",
                        "Brisa del Sur", "Campo Alto", "Los Pinos", "La Estación", "El Trébol",
                        "Puerto Viejo", "Sol de Oro", "Tres Robles", "La Esquina", "El Molino",
                        "Loma Linda", "Valle Hondo", "Los Naranjos", "El Retiro", "Bahía Clara",
                        "La Montaña", "Cerro Norte", "Laguna Mansa", "Las Palmas", "El Cañón",
                    ),
                firstNames =
                    listOf(
                        "Ana", "Andrés", "Beatriz", "Camilo", "Carmen", "César", "Daniela",
                        "Diego", "Elena", "Emilio", "Fabiola", "Felipe", "Gabriela", "Gonzalo",
                        "Helena", "Hernán", "Inés", "Iván", "Julia", "Jorge", "Karina", "Leonor",
                        "Lucía", "Manuel", "Marta", "Mateo", "Nadia", "Nicolás", "Olga", "Óscar",
                        "Paula", "Pedro", "Raquel", "Ramón", "Sara", "Sebastián", "Teresa", "Tomás",
                        "Úrsula", "Valentín", "Verónica", "Víctor", "Ximena", "Yolanda", "Zoe",
                        "Begoña", "Íñigo", "Noemí", "Rocío", "Joaquín",
                    ),
                lastNames =
                    listOf(
                        "Acosta", "Benítez", "Cáceres", "Duarte", "Echeverri", "Fajardo", "Gallego",
                        "Herrera", "Ibáñez", "Jiménez", "Lozano", "Muñoz", "Navarro", "Ordóñez",
                        "Peña", "Quintero", "Ríos", "Salazar", "Toro", "Urrutia", "Valdés",
                        "Zúñiga", "Arango", "Bermúdez", "Cifuentes", "Domínguez", "Estrada",
                        "Figueroa", "Galeano", "Hurtado", "Londoño", "Montoya", "Núñez", "Osorio",
                        "Patiño", "Restrepo", "Sepúlveda", "Trujillo", "Vélez", "Yepes",
                    ),
                secondLocation = "Bodega del fondo",
                bankAccount = "Cuenta del banco",
                paymentMethods =
                    mapOf(
                        "CASH" to "Efectivo",
                        "CARD" to "Tarjeta",
                        "TRANSFER" to "Transferencia",
                        "WALLET" to "Billetera",
                        "ON_ACCOUNT" to "A cuenta",
                        "OTHER" to "Otro",
                    ),
                expenseNotes =
                    listOf(
                        "Pago del mes",
                        "Bolsas y empaques",
                        "Transporte de mercancía",
                        "Reparación de la nevera",
                        "Servicio de agua",
                        "Útiles de aseo",
                    ),
            )

        val ENGLISH =
            SeedVocabulary(
                productNouns =
                    listOf(
                        "Coffee", "Sugar", "Pineapple", "Soap", "Tea", "Cookies", "Rice", "Beans",
                        "Flour", "Oil", "Butter", "Cheese", "Bread", "Milk", "Chocolate", "Oats",
                        "Lentils", "Tuna", "Shampoo", "Toothbrush", "Candle", "Matches",
                        "Detergent", "Napkins", "Sauce", "Jam", "Honey", "Peanuts", "Jalapeño",
                        "Crème fraîche",
                    ),
                productQualifiers =
                    listOf(
                        "sweet", "wholegrain", "mild", "spicy", "classic", "roasted", "natural",
                        "homemade", "fine", "extra", "aged", "family", "budget", "artisan",
                        "creamy", "crunchy", "ground", "rustic", "smoked", "traditional",
                    ),
                productVarieties =
                    listOf(
                        "250 g", "500 g", "1 kg", "6-pack", "12-pack", "small", "medium", "large",
                        "seasonal", "assorted",
                    ),
                longDescription =
                    "special sharing edition for the whole family in recyclable packaging with " +
                        "the usual taste",
                categories =
                    listOf(
                        "Groceries", "Drinks", "Dairy", "Bakery", "Cleaning", "Personal care",
                        "Sweets", "Snacks", "Fruit", "Vegetables", "Meat", "Frozen", "Grains",
                        "Canned goods", "Stationery", "Home", "Pets", "Baby", "Hardware",
                        "Café corner",
                    ),
                supplierPrefixes =
                    listOf(
                        "Wholesale", "Trading", "Farm", "Depot", "Warehouse", "Imports",
                        "Bakehouse", "Dairy", "Goods", "Supply",
                    ),
                supplierNames =
                    listOf(
                        "Green Creek", "Blue Hill", "Lookout Point", "Cedar Glen", "White Stone",
                        "Quiet Mount", "Calm River", "Acacia Row", "Old Gate", "Riverside",
                        "South Breeze", "High Field", "Pine Grove", "Station Yard", "Clover Lane",
                        "Old Harbor", "Golden Sun", "Three Oaks", "Corner Lot", "Old Mill",
                        "Pretty Hill", "Deep Valley", "Orange Grove", "Hideaway", "Clear Bay",
                        "Mountain View", "North Ridge", "Fair Meadow", "Palm Court", "Canyon Edge",
                    ),
                firstNames =
                    listOf(
                        "Abigail", "Adrian", "Bella", "Brandon", "Chloe", "Connor", "Daisy",
                        "Dylan", "Eleanor", "Ethan", "Fiona", "Felix", "Grace", "Gavin", "Hazel",
                        "Henry", "Isla", "Isaac", "Jasmine", "Jonah", "Kayla", "Kevin", "Laura",
                        "Liam", "Maya", "Miles", "Nora", "Nathan", "Olivia", "Owen", "Penny",
                        "Peter", "Quinn", "Rachel", "Ryan", "Sophie", "Simon", "Tessa", "Tyler",
                        "Uma", "Victor", "Violet", "Wendy", "Wyatt", "Xavier", "Yasmin", "Zachary",
                        "Zoë", "Renée", "José",
                    ),
                lastNames =
                    listOf(
                        "Abbott", "Barker", "Carter", "Dalton", "Ellis", "Fletcher", "Garner",
                        "Hayes", "Ingram", "Jennings", "Keller", "Lawson", "Mercer", "Norris",
                        "Osborne", "Porter", "Quigley", "Rowe", "Sutton", "Thornton", "Underwood",
                        "Vaughn", "Walsh", "Yates", "Ashford", "Bramley", "Colby", "Dunmore",
                        "Everett", "Fairbanks", "Goodwin", "Holloway", "Kingsley", "Lockwood",
                        "Merrick", "Pemberton", "Radcliffe", "Stannard", "Whitlock", "Laurière",
                    ),
                secondLocation = "Back storeroom",
                bankAccount = "Bank account",
                paymentMethods =
                    mapOf(
                        "CASH" to "Cash",
                        "CARD" to "Card",
                        "TRANSFER" to "Transfer",
                        "WALLET" to "Wallet",
                        "ON_ACCOUNT" to "On account",
                        "OTHER" to "Other",
                    ),
                expenseNotes =
                    listOf(
                        "Monthly payment",
                        "Bags and packaging",
                        "Goods delivery",
                        "Fridge repair",
                        "Water service",
                        "Cleaning supplies",
                    ),
            )

        val PORTUGUESE =
            SeedVocabulary(
                productNouns =
                    listOf(
                        "Café", "Açúcar", "Abacaxi", "Sabão", "Chá", "Biscoitos", "Arroz",
                        "Feijão", "Farinha", "Óleo", "Manteiga", "Queijo", "Pão", "Leite",
                        "Chocolate", "Aveia", "Lentilhas", "Atum", "Xampu", "Escova", "Vela",
                        "Fósforos", "Detergente", "Guardanapos", "Molho", "Geleia", "Mel",
                        "Amendoim", "Maçã", "Limão",
                    ),
                productQualifiers =
                    listOf(
                        "doce", "integral", "suave", "picante", "clássico", "torrado", "natural",
                        "caseiro", "fino", "extra", "curado", "família", "econômico", "artesanal",
                        "cremoso", "crocante", "moído", "rústico", "defumado", "tradicional",
                    ),
                productVarieties =
                    listOf(
                        "250 g", "500 g", "1 kg", "x6", "x12", "pequeno", "médio", "grande",
                        "da estação", "sortido",
                    ),
                longDescription =
                    "edição especial para compartilhar em família com embalagem reciclável e o " +
                        "sabor de sempre",
                categories =
                    listOf(
                        "Mercearia", "Bebidas", "Laticínios", "Padaria", "Limpeza",
                        "Higiene pessoal", "Doces", "Salgadinhos", "Frutas", "Verduras", "Carnes",
                        "Congelados", "Grãos", "Enlatados", "Papelaria", "Casa", "Animais",
                        "Bebês", "Ferragens", "Cafeteria",
                    ),
                supplierPrefixes =
                    listOf(
                        "Distribuidora", "Comercial", "Granja", "Armazém", "Depósito",
                        "Importadora", "Panificadora", "Laticínios", "Produtos", "Atacado",
                    ),
                supplierNames =
                    listOf(
                        "Riacho Verde", "Colina Azul", "Mirante", "Canhada", "Pedra Branca",
                        "Monte Sereno", "Rio Manso", "Acácias", "Portal", "Ribeira",
                        "Brisa do Sul", "Campo Alto", "Pinheiros", "Estação", "Trevo",
                        "Porto Velho", "Sol Dourado", "Três Carvalhos", "Esquina", "Moinho",
                        "Morro Lindo", "Vale Fundo", "Laranjeiras", "Retiro", "Baía Clara", "Serra",
                        "Cerro Norte", "Palmeiras", "Cânion", "Lagoa Mansa",
                    ),
                firstNames =
                    listOf(
                        "Adriana", "Antônio", "Bárbara", "Bruno", "Camila", "Caio", "Débora",
                        "Davi", "Elisa", "Eduardo", "Fernanda", "Fábio", "Giovana", "Gustavo",
                        "Heloísa", "Henrique", "Isadora", "Igor", "Joana", "João", "Larissa",
                        "Lucas", "Mariana", "Márcio", "Natália", "Nelson", "Otávia", "Otávio",
                        "Patrícia", "Paulo", "Rafaela", "Rodrigo", "Sílvia", "Sérgio", "Tânia",
                        "Thiago", "Valéria", "Vinícius", "Yara", "Wagner", "Aurélia", "Conceição",
                        "Simão", "Estêvão", "Lívia", "Graça", "Inácio", "Lúcia", "Mônica", "Renan",
                    ),
                lastNames =
                    listOf(
                        "Albuquerque", "Barros", "Cardoso", "Dantas", "Esteves", "Falcão",
                        "Guimarães", "Holanda", "Igrejas", "Jardim", "Leão", "Magalhães",
                        "Nogueira", "Oliveira", "Peixoto", "Queiroz", "Rebelo", "Sampaio",
                        "Tavares", "Uchoa", "Vasconcelos", "Xavier", "Assunção", "Brandão",
                        "Coimbra", "Damasceno", "Fontes", "Gusmão", "Lacerda", "Meireles",
                        "Nascimento",
                        "Pimentel", "Quintão", "Ramalho", "Siqueira", "Teixeira", "Valadão",
                        "Viana", "Conceição", "França",
                    ),
                secondLocation = "Depósito dos fundos",
                bankAccount = "Conta do banco",
                paymentMethods =
                    mapOf(
                        "CASH" to "Dinheiro",
                        "CARD" to "Cartão",
                        "TRANSFER" to "Transferência",
                        "WALLET" to "Carteira",
                        "ON_ACCOUNT" to "Fiado",
                        "OTHER" to "Outro",
                    ),
                expenseNotes =
                    listOf(
                        "Pagamento do mês",
                        "Sacolas e embalagens",
                        "Frete de mercadoria",
                        "Conserto da geladeira",
                        "Serviço de água",
                        "Material de limpeza",
                    ),
            )
    }
}
