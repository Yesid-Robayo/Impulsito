Repor é voltar a ter o que se vende antes que acabe. {app} sugere quanto comprar a partir do que se vende a cada dia e do **Estoque mínimo** de cada produto. É só uma sugestão: não cria nenhuma compra.

## Onde fica

- Em **Produtos**, **Estoque**, cada linha traz **Repor**, que abre uma nova compra com esse produto. Acima da lista, **Reposição sugerida** abre a lista completa.
- Em **Negócio**, **Reposição sugerida**.
- No detalhe de um produto, **Registrar compra** também abre a compra.

O **Estoque mínimo** é definido ao criar ou editar o produto. Com ele, o produto aparece em **Acabando** quando fica abaixo desse número. Os avisos e os ajustes estão em [Estoque, ajustes e contagens](help_stock_counts).

## Como é calculado

{app} olha o que foi vendido nos últimos 30 dias e calcula quanto sai por dia. A sugestão é o que dá para os dias de **Cobrir**, mais o mínimo, menos o que há.

Por exemplo: se em 30 dias foram vendidos 60, saem 2 por dia. Com **Cobrir** em 30 dias são necessários 60, mais um mínimo de 10, são 70. Se restam 20, a sugestão é comprar 50.

- Se o produto é comprado por caixa ou outra unidade de compra, a sugestão sobe para caixas inteiras.
- Um produto sem vendas nesses dias diz **Sem vendas recentes**. Só aparece se estiver abaixo do mínimo, e com 0, para preencher à mão.
- Sem nenhuma venda, a lista diz **São necessárias vendas para sugerir**.

## A lista

Os produtos vão agrupados pelo fornecedor habitual, com **Sem fornecedor** no final, e em cada grupo primeiro o que está **Esgotado**. Cada grupo mostra o **Custo estimado**, e cada linha, o que resta e para quantos dias dá. É possível filtrar por **Fornecedor** e por **Categoria**.

A quantidade de cada linha pode ser mudada antes de comprar. No final de cada grupo:

- **Compartilhar lista** prepara a lista para enviar por outro app.
- **Criar compra** abre a nova compra com essas quantidades. Nada é salvo até tocar em **Registrar compra**.

## O que é produzido

Um produto que o negócio faz com uma receita não é comprado de um fornecedor. Na linha dele no estoque, na reposição sugerida e no detalhe dele aparece **Produzir**, que abre a produção com esse produto, e ele fica fora de **Criar compra** e da lista compartilhada. A produção é explicada em [Receitas e produção](help_recipes_production).
