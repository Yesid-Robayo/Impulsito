Cada produto tem nome, preço de venda e, se for conhecido, o custo. Com o custo, {app} calcula a margem e o lucro de cada venda. Sem custo, a venda é registrada do mesmo jeito, mas o lucro dela não é real: o produto é marcado como **Sem custo**.

## Criar um produto

- **Nome**, **Categoria** e unidade.
- **Preço de venda**: o que se cobra.
- Custo: quanto custou cada unidade. Depois, as compras o atualizam.
- **Estoque inicial**: o que há hoje.
- **Estoque mínimo**: o número abaixo do qual o produto está acabando.
- **Uso**: **Para venda**, **Insumo** ou **As duas coisas**. Um insumo não aparece ao vender, mas é comprado, contado e entra em receitas e produção.

**Sugerir preço** propõe um preço a partir do custo e da margem desejada do perfil do negócio, em **Configurações**.

## Mudar o custo

Um produto com movimentos não muda o custo em **Editar**: ali ele aparece, mas quem o calcula são as compras, como uma média do que há em estoque. Para mudá-lo:

- Se subiu o que se paga na compra, registra-se a compra em **Nova compra** com o custo novo, e a média se ajusta sozinha.
- Se o custo está errado ou nunca foi informado, **Corrigir custo**, abaixo do custo em **Editar** ou no menu ⋮ do detalhe, fixa o custo novo a partir de agora. As vendas anteriores mantêm o lucro, e o valor do estoque muda.
- Para saber quanto custaria comprar hoje, sem mexer no lucro já registrado, existe o **Custo de reposição**: ver [custo médio e custo de reposição](help_replacement_cost).

## Mudar preços

- Uma mudança de preço só afeta as vendas novas: as anteriores mantêm o preço com que foram vendidas.
- A atualização em massa muda muitos preços de uma vez, por porcentagem e com arredondamento, e **Desfazer a última** a reverte.

## Arquivar

Um produto com movimentos não é apagado: é arquivado. Deixa de aparecer ao vender, e o histórico dele é mantido.
