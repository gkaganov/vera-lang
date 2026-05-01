package org.greg

class ByteArrayClassLoader(
    private val classes: Map<String, ByteArray>
) : ClassLoader(ByteArrayClassLoader::class.java.classLoader) {

    override fun findClass(name: String): Class<*> {
        val bytes = classes[name] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}
