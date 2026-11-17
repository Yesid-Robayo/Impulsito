Uma tabela de preços guarda preços diferentes para um tipo de cliente, como os atacadistas, sem duplicar produtos. Sempre existe a **Tabela geral**: os preços dela são os preços de venda dos produtos.

## Criar uma tabela

Em **Negócio**, **Tabelas de preços**, tocar em **Nova tabela**:

- **Nome**: por exemplo, atacado.
- **Tipo**: **Fixa**, com um preço escrito para cada produto, ou **Relativa**, que calcula os preços a partir de uma **Tabela base** com um **Ajuste** em porcentagem. Com um ajuste de -10, um produto que na base custa 100 fica em 90.
- **Salvar tabela**.

Em uma tabela fixa, um produto sem preço próprio usa o preço geral e indica isso com **Preço geral**. Em uma relativa, cada preço aparece como **Calculado**.

## Mudar um preço

No detalhe de uma tabela fixa, tocar em um produto, escrever o **Preço nesta tabela** e tocar em **Salvar**. Enquanto se escreve, aparece a **Margem resultante**. **Remover preço próprio** devolve o produto ao preço geral. Na **Tabela geral**, mudar um preço muda o preço de venda do produto.

**Atualizar preços desta tabela** muda muitos preços de uma vez: por **Porcentagem**, **Valor**, **Margem-alvo** ou **Por cotação**.

Cada mudança fica no histórico do produto, na aba **Preços** do detalhe dele. As vendas anteriores não mudam: cada uma guarda o preço com que foi vendida.

## Usar a tabela ao vender

- No detalhe do cliente, **Editar** → **Tabela de preços** atribui uma a ele. Ao escolher esse cliente em uma venda, os preços passam a ser os da tabela dele.
- Em **Vender**, dentro do carrinho, **Tabela** escolhe outra para essa venda.

Essas opções aparecem quando há mais de uma tabela ativa. No menu ⋮ de uma tabela, **Arquivar** a retira: os clientes dela voltam à **Tabela geral** e ela deixa de ser oferecida em vendas novas. **Reativar** a recupera.

## Moeda de referência

**Moeda de referência**, em **Negócio**, serve para pensar preços a partir de outra moeda. Não muda a moeda do negócio: vendas, custos e saldos continuam nela.

1. **Escolher moeda** e os **Decimais** dela.
2. **Registrar cotação**: a **Cotação**, a **Data** e uma **Nota** com a origem. É escrita à mão: {app} não a baixa. Cada cotação nova se soma ao **Histórico** sem apagar as anteriores.
3. Em cada produto, o **Preço de referência** guarda o preço dele nessa moeda.
4. **Reajustar preços com esta cotação** prepara a atualização de preços para revisá-la antes de aplicá-la. Nenhum preço muda sozinho.
