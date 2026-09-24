# radio-mapper

Planejamento e monitoramento de rede de rádio ponto-a-ponto e ponto-multiponto
para provedores (WISP), com o relevo entrando na conta.

O programa faz três coisas que normalmente ficam em ferramentas separadas:

1. **Mapeia a rede existente** — conecta nos rádios por SSH, lê quem está
   associado a quem, com que sinal, e desenha a topologia no mapa.
2. **Calcula o que o rádio alcança de verdade** — não o número que alguém
   digitou no cadastro, mas o resultado de orçamento de enlace mais visada
   sobre o terreno, em todas as direções.
3. **Mostra o terreno** — perfil lateral com zona de Fresnel, e uma cena 3D
   navegável com as ortofotos vestidas no relevo.

Interface em JavaFX, desktop, sem servidor. Um projeto é um arquivo `.rmap`.

---

## Por que existe

O alcance que um rádio "tem" costuma ser um número herdado de planilha. Ele
controla o desenho no mapa e mais nada — não passa por nenhuma conta de RF, e
não sabe que existe um morro no caminho. O resultado é a equipe subir numa
torre para instalar um cliente que não vai fechar.

Aqui o alcance é **calculado**. Cada direção dentro da abertura da antena vira
um raio que avança até esbarrar no primeiro dos dois limites reais:

- **o orçamento de enlace** — potência, ganho das duas pontas, perda de cabo e
  perda de percurso pela distância;
- **o terreno** — um morro no caminho encerra a direção ali, por mais potência
  que sobre.

O que sai é um limite superior honesto: onde isto diz que não chega, não chega.

---

## O que faz

### Descoberta e monitoramento

- SSH em rádios **Ubiquiti** (AirOS 6 e 8) e **MikroTik** (RouterOS v6 e v7
  wifiwave2), mais roteadores RouterOS.
- Lê vizinhos associados, sinal, e monta a topologia sozinho.
- Ping em equipamento que não fala SSH.
- Monitor de tráfego por interface, a partir dos contadores de bytes.
- Histórico por rádio e notificação por e-mail quando algo cai.

### Mapa

- Mapa base OpenStreetMap, satélite ou relevo; ou modo sem mapa, com a imagem
  sendo o próprio mundo.
- **Várias ortofotos sobrepostas**, cada uma com posição, opacidade, trava e
  ordem na pilha. GeoTIFF com georreferência se posiciona sozinho.
- Nuvem de pontos `.PLY` como fonte de altitude, com o contorno da área
  levantada desenhado no mapa.
- Pontos de rede, feixes configurados e enlaces, com distância na linha.

### Análise de RF

- **Perfil de enlace**: terreno entre as duas antenas, linha de visada,
  primeira zona de Fresnel, folga mínima, desnível, mira de cada antena,
  sinal estimado × sinal medido.
- Arrastar a torre no perfil para procurar uma posição livre, com um fantasma
  no mapa mostrando onde ela cairia, e aplicar quando servir.
- **Alcance simulado**: varredura em todas as direções, recortada por nível de
  sinal (-55 / -65 / -75 dBm), desenhada como grade sobre o mapa.
- **Recomendação de altura**: refaz a cobertura para várias alturas de antena e
  diz até onde subir ainda paga — o joelho da curva, não o topo.
- Enlaces planejados, para simular o que ainda não existe.

### Terreno em 3D

- Cena navegável com as ortofotos vestidas no relevo.
- Alcance simulado pintado sobre o terreno, por nível de sinal.
- Gizmo de eixos X/Y/Z: clicar numa ponta leva a vista para aquele lado.
- Filtro de vegetação, marcação da área da nuvem, qualidade ajustável.

---

## Decisões que valem saber

Coisas que parecem detalhe e mudam o resultado.

### A sombra é recortada do desenho

O alcance de uma direção é um número só, e desenhar com ele obriga a preencher
tudo entre a antena e aquele ponto — inclusive o fundo de uma cava que a
própria varredura marcou como escondido. Neste projeto de teste, **71% do que o
desenho antigo preenchia estava na sombra do relevo**.

A cobertura é uma **grade**, não um contorno: a sombra de um terreno acidentado
tem forma de mancha cheia de furos, que nenhuma sequência de vértices por
direção representa. O número de direções varridas sai da geometria — quantos
raios são precisos para o arco entre dois vizinhos, na borda do alcance, não
passar de 6 m.

### Altura de mastro e altitude absoluta são coisas diferentes

"A antena está a 30 m" e "a antena está a 712 m" são as duas formas que
aparecem na prática, e confundi-las põe a antena centenas de metros fora do
lugar. A escolha é explícita no cadastro e na tela de alcance, com o programa
avisando quando o número tem cara de estar no modo errado.

Altitude absoluta também é a saída quando **o relevo está errado**: o número já
é a cota final e o terreno não é somado.

### Onde não há dado, não se inventa chão

Fora da área levantada o terreno fica vazado, e não preenchido com uma média.
Um planalto liso com cara de terreno real levaria alguém a decidir um enlace
olhando para relevo que ninguém mediu.

### A nuvem é de superfície, não de terreno

Uma nuvem de drone enxerga a copa das árvores, não o chão. No 3D há um filtro
de solo (abertura morfológica) que tira a vegetação do desenho — mas **a
obstrução continua sendo calculada com ela**, porque para o rádio a árvore
obstrui de verdade. O rodapé diz quando o filtro está ligado.

### Visada geométrica não é Fresnel livre

O alcance simulado usa visada geométrica: uma direção pode aparecer como
alcançável e ainda assim render mal. Para o veredito de um enlace específico, o
perfil lateral é o lugar — ele mostra Fresnel.

---

## Como rodar

**Requisitos:** JDK 17 ou superior (testado em 21) e Maven. O JavaFX vem pelas
dependências, não precisa instalar à parte.

```bash
mvn package
java -jar target/radio-mapper.jar
```

O `package` gera um jar com tudo dentro (`maven-shade-plugin`), com
`Launcher` como classe principal — o `Launcher` existe porque uma classe que
estende `Application` não pode ser o main de um jar sombreado.

Para desenvolver:

```bash
mvn -o compile          # compila
mvn -o package -DskipTests
```

---

## Estrutura

```
com.colmeia.radiomapper
├── model/       Projeto, ponto, rádio, enlace, camadas de imagem — o que é gravado no .rmap
├── geo/         Mercator, UTM, tiles, GeoTIFF, nuvem .PLY, cadeia de fontes de altitude
├── rf/          Orçamento de enlace, cobertura por varredura, escolha da outra ponta
├── ssh/         Sondas por fabricante e versão, pool de sessões
├── discovery/   Montagem da topologia a partir do que as sondas leram
├── io/          Leitura e gravação do projeto, carga de imagem, salvamento automático
├── history/     Histórico por rádio
├── notify/      Notificação por e-mail
├── ui/          Telas JavaFX (mapa, perfil, 3D, diálogos)
└── util/        Log e preferências
```

### A cadeia de altitude

As fontes são consultadas em ordem de qualidade: **PLY → GeoTIFF → relevo do
mapa**. Cada uma devolve `null` fora da sua área, então a consulta desce a
cadeia naturalmente — dentro da área do drone vale o drone, um metro além dela
já vale a próxima fonte, sem buraco no meio.

O relevo global vem dos tiles Terrarium (dados SRTM/ASTER), com cache em disco
em `~/.radio-mapper/terrain`.

### Dois espaços de coordenadas

- **Modo mapa**: x/y dos pontos são metros de Web Mercator (EPSG:3857).
- **Modo imagem** (sem mapa base): x/y são pixels da imagem, e a escala em
  metros por pixel é informada nas configurações.

Trocar de modo com pontos já marcados reinterpreta as coordenadas, por isso a
interface confirma antes.

---

## Dependências

| O quê | Para quê |
|---|---|
| JavaFX 21 | interface, canvas 2D e cena 3D |
| sshj | SSH nos rádios e roteadores |
| Jackson | leitura e gravação do `.rmap` |
| imageio-tiff (TwelveMonkeys) | GeoTIFF de ortofoto e de relevo |
| jakarta.mail | notificação por e-mail |

---

## Estado

Versão 0.1.0, em uso interno. Não há suíte JUnit no repositório: a verificação
foi feita com harnesses que abrem as telas de verdade e medem o resultado
(cobertura contra um teste de visada independente, símbolos em pixels na tela,
ida e volta do `.rmap`, sobreposição de imagens amostrada pixel a pixel).
Migrá-los para JUnit é trabalho pendente.

## Licença

Sem licença definida. Todos os direitos reservados até que se decida uma.
