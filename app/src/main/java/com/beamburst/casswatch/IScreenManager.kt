// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch

import com.beamburst.casswatch.data.repository.GShockRepository

interface IScreenManager {
    fun showContentSelector(repository: GShockRepository)
    fun showPreConnectionScreen()
    fun showInitialScreen()
    fun showError(message: String)
}