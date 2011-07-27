/**
 *  Copyright 2011 Alexandru Craciun, Eyal Kaspi
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.stjs.javascript;

/**
 * This interface represents a normal object in javascript (that acts as a map). The key is always a String. The value may be typed. The
 * iteration is done on the keys to have the javascript equivalent of <br>
 * <b>for(var key in map)</b> <br>
 * The methods are prefixed with $ to let the generator know that is should generate braket access instead, i.e <br>
 * map.$get(key) => map[key] <br>
 * map.$put(key, value) => map[key]=value
 * @author acraciun
 */
public interface Map<V> extends Iterable<String> {
	public V $get(String key);

	public void $put(String key, V value);
}