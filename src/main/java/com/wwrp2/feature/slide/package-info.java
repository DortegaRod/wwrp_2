/**
 * Deslizamiento al estilo Black Ops 3: al pulsar la tecla de deslizarse (C por defecto)
 * mientras se esprinta, el jugador se lanza por el suelo con un impulso que se va frenando,
 * puede girar un poco, disparar, y cortar el deslizamiento con un salto para llevarse parte
 * de la velocidad.
 *
 * <p>El movimiento lo calcula el cliente del propio jugador (como todo el movimiento en
 * Minecraft) en {@link com.wwrp2.feature.slide.client.SlideController}. El cliente avisa al
 * servidor con {@link com.wwrp2.feature.slide.SlideRequestPacket}; el servidor lo valida en
 * {@link com.wwrp2.feature.slide.SlideManager}, baja la hitbox del jugador y reenvía un
 * {@link com.wwrp2.feature.slide.SlideStatePacket} al resto de clientes, que reproducen la
 * animación. Los números que definen cómo se siente están todos en
 * {@link com.wwrp2.feature.slide.SlideRules}.</p>
 *
 * <p>Otras features pueden preguntar en el servidor si alguien se está deslizando con
 * {@link com.wwrp2.feature.slide.SlideManager#isSliding}.</p>
 *
 * @author JosedaLoC
 */
package com.wwrp2.feature.slide;
