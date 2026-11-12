/**
 * Killfeed de muertes definitivas de jugadores: conserva el mensaje localizado de Minecraft,
 * añade un prefijo y sonido, un anuncio superior de víctima y eliminador durante cuatro
 * segundos y un rayo de dos segundos para observadores cercanos, sin crear entidades en el mundo.
 * El anuncio de los caídos usa un retrato de la skin, laureles y una proyección azulada,
 * con nombre destacado, eliminador y un golpe sonoro grave de aviso.
 * Su altura se limita al veintidós por ciento de la pantalla, respetando la escala de GUI.
 *
 * <p>Solo se activa durante {@code RoundPhase.COMBAT}, con estado sincronizado desde el
 * servidor también al conectar. Al salir de combate limpia avisos y efectos pendientes. Respeta
 * {@code showDeathMessages} y la visibilidad de mensajes del equipo. No anuncia derribos
 * cuya muerte se haya cancelado. El rayo se muestra en la misma dimensión, a 256 bloques;
 * el aviso sonoro acompaña al mensaje recibido. No cambia las reglas del mundo.</p>
 *
 * @author Dilles
 */
package com.wwrp2.feature.killfeed;
