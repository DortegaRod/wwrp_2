/**
 * Lógica base de rondas: la máquina de estados central de la partida
 * (espera → preparación → combate → finalización → espera).
 *
 * <p>Los OP la manejan con {@code /ronda iniciar}, {@code /ronda siguiente} y
 * {@code /ronda parar}, y cualquiera puede ver cómo va con {@code /ronda estado}. Esta feature
 * no pone reglas en ninguna fase ni lleva cuenta atrás: las demás features escuchan
 * {@link com.wwrp2.feature.rondas.RoundPhaseChangeEvent} para reaccionar a cada fase y usan
 * {@link com.wwrp2.feature.rondas.RoundManager} para consultarla o cambiarla. Por ejemplo, para
 * cerrar el combate cuando solo queda un jugador vivo:</p>
 *
 * <pre>{@code
 * if (RoundManager.phase() == RoundPhase.COMBAT) {
 *     RoundManager.advance(server);
 * }
 * }</pre>
 *
 * @author Mega
 */
package com.wwrp2.feature.rondas;
