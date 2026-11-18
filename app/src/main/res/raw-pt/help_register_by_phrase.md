Em vez de abrir cada tela, é possível escrever ou ditar o que aconteceu, como «vendi 3 pães e 2 sucos em dinheiro». O {app} mostra em um cartão o que entendeu, e nada é registrado até tocar em **Registrar**.

## Como usar

1. Tocar em **Dizer**, o botão redondo do microfone, embaixo à direita, no microfone do widget ou no atalho do ícone do {app}.
2. Falar: a tela de escuta mostra a frase enquanto ela é dita. Ao terminar, ou ao tocar em **Pronto**, aparece o cartão com a operação, cada produto com sua quantidade e seu valor, o pagamento e o total.
3. Conferir e tocar em **Registrar**.

Na primeira vez, o {app} explica para que usa o microfone e o Android pede a permissão. Sem permissão, ou com **Escrever**, abre a folha para escrever em **O que aconteceu** ou ditar com o microfone do teclado, e depois tocar em **Entender**. Para vender sem frase, **Vender manualmente**.

## O que se pode dizer

- Vendas: «vendi 2 quilos de arroz e uma dúzia de ovos a prazo para a Maria».
- Compras: «comprei 10 quilos de farinha por 500 na distribuidora».
- Algo novo, com o tipo e a categoria: «comprei 2 quilos de farinha a 50, é um insumo, na categoria matérias primas». O cartão o mostra como **Novo**, já como insumo e nessa categoria, e ele é criado ao tocar em **Registrar**. Se a categoria não existe, o cartão a mostra como **Categoria nova**, e ela é criada junto com o produto. **Escolher categoria** a troca.
- Despesas: «paguei a luz 80», «gastei 15 de táxi».
- Produção: «fiz 20 pastéis», «assei 50 pães», ou várias de uma vez: «fiz 10 pastéis e 5 pães». Com o que levou: «fiz um bolo com 200 gramas de farinha e 3 ovos».
- Estoque: «estragaram 2 pães», «venceram 3 leites», «dei um bolo de brinde», «sobraram 40 pães».
- Pagamentos: «a Maria me pagou 20», «paguei 50 para o Seu Ramiro».
- Receitas: «o bolo leva 200 gramas de farinha, 3 ovos e 100 gramas de açúcar», «para fazer 10 pastéis uso 1 quilo de farinha».
- Produtos: «produto novo vela de canela a 15, custo 6».
- Custo e preço: «o custo do pão agora é 4», «o pão agora sai a 6».

É possível citar vários produtos em uma frase, com números em algarismos ou por extenso («meio quilo», «meia dúzia»). Uma frase é uma só operação: venda e compra se dizem separadas.

## Produzir com o que leva

- Com receita, «fiz 20 pastéis» tira os insumos da receita, pela quantidade produzida.
- Algo que não existe é criado ao registrar, com o que foi dito como receita: «fiz um bolo com 200 gramas de farinha» cria o bolo e o produz.
- Sem receita, o cartão avisa e pede para dizer o que leva. Com o cartão aberto, «leva 200 gramas de farinha e 3 ovos», ou só «200 gramas de farinha e 3 ovos». Ao tocar em **Registrar**, é produzido e o que foi dito fica como a receita, por unidade: com «fiz 2 bolos com 400 gramas de farinha», a receita fica com 200 gramas por bolo.
- Com receita e outra quantidade, como «fiz um bolo com 250 gramas de farinha» quando a receita pede 200, é produzido com o que foi dito e o cartão mostra o que a receita pede. O que não foi nomeado sai da receita, e um insumo que a receita não leva entra só nesta produção. **Atualizar a receita** vem desligado; ligado, a receita também fica com o que foi dito.
- As unidades da mesma classe são convertidas sozinhas: 200 gramas de uma farinha controlada por quilo são 0,200 kg, e o mesmo com mililitros e litros, ou com uma embalagem salva, como uma caixa. Entre classes diferentes, como litros de algo controlado por quilo, a quantidade fica para completar numa unidade do produto.
- A libra, a onça e o galão mudam conforme o lugar, então o {app} não os supõe. Na primeira vez que se dizem gramas de algo controlado por libra, a linha pergunta quanto pesa uma libra daquele produto, com os valores habituais ou **Outro valor**, e converte na hora. Ao registrar, a medida fica no produto e a partir daí converte sozinha.
- Um insumo que não existe e não se parece com nenhum aparece como **Novo** e é criado como insumo ao registrar. Se parece com outros, escolhe-se um, ou **Criar "…"**.
- Tudo é salvo junto: os produtos novos, a receita e a medida com a produção, e as produções de uma mesma frase. Se algo falhar, nada fica.

## Se algo não ficou certo

- Se um produto parece com vários, o cartão mostra as opções para escolher, com **Criar "…"** como mais uma. Se não existe e não se parece com nenhum, fica como **Novo** e é criado ao registrar; **Escolher outro** o troca por um do catálogo.
- Tocar em uma linha abre **Corrigir**: muda a quantidade, a unidade ou o preço, **Trocar produto** ou **Remover do cartão**.
- Uma venda de um produto sem preço, como um criado ao produzi-lo, pede o preço: «a 15 mil», ou em **Corrigir**.
- Também se corrige com uma frase curta, sem repetir tudo: «não, eram 3», «a farinha era de milho», «a farinha é insumo», «tira os refrigerantes», «mais 2 ovos», «em dinheiro», «não, foi uma compra».
- Com o cartão aberto, o que se diz soma ou corrige, e nunca apaga o que já está. O que se diz sem verbo, como «um quilo de farinha» ou «e 2 de açúcar a 3 mil», soma à operação do cartão. Uma frase da mesma operação, como «tenho um quilo de farinha» sobre uma contagem, também soma os produtos.
- Um produto que já está no cartão é corrigido com a quantidade nova. Em uma venda ou uma compra, soma com «e» ou «outros»: «e outros 2 pães». Em uma contagem, o que se diz é o que há.
- Se for dita outra operação, como «vendi 2 pães» com uma compra aberta, o cartão pergunta se começa outro: **Começar outro** descarta o cartão atual, e **Continuar com este** descarta a frase nova. «não, foi uma venda» muda a operação sem perguntar.
- No alto do cartão, **Operação** passa de venda para compra, contagem ou produção com um toque, e as linhas ficam.
- Se for dito o tipo ou a categoria de um produto que já existe, como «a farinha é insumo», o cartão não muda: isso se altera no produto, com **Abrir produto**.
- Uma linha só sai com «tira…» ou com **Remover do cartão**.
- **Começar de novo** apaga o cartão.

O que falta fica marcado no lugar, e **Registrar** não salva até completar. O que se registra fica igual a se tivesse sido feito na sua tela, e se anula como sempre.

## Se não escutar

- Se não se ouviu nada, **Ouvir de novo**, ou falar mais perto do telefone.
- Se a permissão do microfone foi negada, a folha oferece **Abrir configurações do telefone** para concedê-la, ou é possível ditar com o microfone do teclado.
- O {app} escuta com o serviço de voz do telefone. Se o telefone não o tiver, ou não tiver o idioma, fica o teclado. Para o teclado ditar: nas configurações dele, ativar a digitação por voz e baixar o idioma para usar sem conexão; nas configurações do telefone, em aplicativos, conferir se o app do serviço de voz está instalado e ativado.

**O microfone não funciona?**, na folha, abre esta ajuda.

## Ouvir ao tocar em Dizer

Vem ligado. Em **Configurações**, **Vendas e cobrança**, **Ouvir ao tocar em Dizer** se desliga: assim, **Dizer** abre a folha para escrever ou ditar com o teclado. O {app} usa o microfone só enquanto a tela de escuta está aberta e não guarda áudio. O serviço de voz do telefone transforma a voz em texto e pode usar a internet se não tiver o idioma baixado.
