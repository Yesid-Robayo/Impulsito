Um orçamento oferece preços a um cliente, e um pedido acompanha uma encomenda até a entrega. Nenhum dos dois é uma venda: o estoque e os números mudam só quando a venda é registrada. Os dois estão em **Negócio** e, depois que existe o primeiro, também em **Registros**.

## Orçamentos

1. Em **Orçamentos**, tocar em **Novo orçamento**. Com uma venda já montada, ⋮ → **Salvar como orçamento** a leva com os produtos.
2. Escolher os produtos como em uma venda e tocar em **Salvar orçamento**. É possível acrescentar **Cliente**, **Válido até** e **Observação**, tudo opcional.
3. No detalhe, **Converter em venda** abre a venda com os produtos e o cliente. Se algum preço mudou, escolhe-se **Usar preço do orçamento** ou **Usar preço atual**.
4. Ao registrar a venda, o orçamento fica **Convertido**. Se sair sem registrar, ele continua aberto.

Um orçamento com a data passada aparece como **Vencido** e ainda pode ser convertido. No menu dele estão **Editar**, **Compartilhar orçamento**, que prepara um PDF sem custos nem lucros, e **Cancelar orçamento**, que o deixa no histórico sem poder ser convertido.

## Pedidos

1. Em **Pedidos**, tocar em **Novo pedido**, ou ⋮ → **Salvar como pedido** a partir de uma venda.
2. **Salvar pedido** pede, tudo opcional, **Cliente**, **Data de entrega**, **Endereço de entrega**, **Observação de entrega** e **Valor do frete**.
3. O pedido passa de **Pendente** a **Pronto** com **Marcar como pronto**, e a **Entregue** com **Registrar entrega**. Em **Pronto**, o menu oferece **Voltar a pendente**.

- Cada linha mostra o **Reservado** e avisa se falta estoque, sem impedir nada. Reservar não mexe no estoque: ele baixa só na entrega.
- **Registrar sinal**, com um cliente escolhido, guarda um pagamento adiantado como saldo a favor desse cliente. O resumo mostra o **Sinal** e o valor **A receber na entrega**.
- **Registrar entrega** abre a venda com os produtos, o cliente e o frete, e o sinal cobre a parte dele. Ao registrá-la, o pedido fica **Entregue**.
- Com a data passada, a linha diz **Entrega atrasada**. O status não muda sozinho.
- **Cancelar pedido** deixa de reservar as unidades, e um sinal fica como saldo a favor do cliente.

Um orçamento ou um pedido não leva adicionais: eles entram como produtos à parte.

## Vendas registradas em outro sistema

{app} não emite documentos fiscais nem se conecta a nenhuma autoridade. **Pendentes de faturar**, em **Negócio**, só ajuda a manter o controle das vendas que são registradas em outro sistema: cada uma fica **Pendente**, **Registrada à parte** ou **Não se aplica**, com a **Referência externa**, se precisar. Mais em [O que {app} não cobre](help_not_accounting).
